package machine;

import machine.api.ApiResponses;
import machine.api.ServerClient;
import machine.api.ServerUnavailableException;
import machine.config.MachineSettings;
import machine.hardware.HardwareException;
import machine.hardware.SimulatedCardReader;
import machine.hardware.SimulatedFingerprintScanner;
import machine.queue.PendingVote;
import machine.queue.VoteQueue;
import machine.queue.VoteSyncWorker;
import machine.ui.KioskFrame;
import machine.ui.Theme;

import javax.swing.*;
import java.awt.Image;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The voting terminal.
 *
 * <p>Drives the voter journey the polling booth requires: card, identity confirmation,
 * fingerprint, ballot, confirmation, receipt. Holds no election logic of its own — which
 * candidate list a voter sees and whether they are entitled to vote are both decided by the
 * server from the voter's own registration. That is what lets a voter walk into any booth
 * and get the right ballot.
 *
 * <h2>What the terminal does and does not decide</h2>
 * It does not decide whether a fingerprint matched, whether a voter has already voted, or
 * which candidates are on the ballot. It captures input, transmits it over an authenticated
 * encrypted channel, and renders the answer. The one thing it owns is durability: once a
 * voter confirms, the vote is written to encrypted local storage before the screen says
 * "recorded", so no confirmed vote can be lost to a network failure.
 *
 * <h2>Threading</h2>
 * Swing's event thread never performs I/O. Every server call runs on a worker thread and
 * comes back through {@code invokeLater}, so a slow or unreachable server leaves the screen
 * responsive instead of freezing mid-vote.
 */
public class VotingMachineApp implements KioskFrame.Listener {

    private final MachineSettings settings;
    private final ServerClient    server;
    private final VoteQueue       queue;
    private final VoteSyncWorker  syncWorker;
    private final KioskFrame      frame;

    private final SimulatedCardReader         cardReader = new SimulatedCardReader();
    private final SimulatedFingerprintScanner scanner    = new SimulatedFingerprintScanner();

    /** Serialises server calls so the terminal never has two voter operations in flight. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "terminal-worker");
        thread.setDaemon(true);
        return thread;
    });

    // Per-voter state, only ever touched on the event thread.
    private String                       voterId;
    private String                       voterName;
    private String                       biometricToken;
    private ApiResponses.SessionResult   session;
    private ApiResponses.CandidateOption chosenCandidate;
    private Integer                      activeElectionId;
    private String                       activeElectionName = "—";

    private final Timer countdownTimer;
    private int         secondsRemaining;

    /**
     * Party symbols, fetched once per candidate and kept for the life of the run. The
     * candidate list does not change during a polling day, so there is nothing to
     * invalidate the cache for; every voter after the first at a booth pays no extra
     * network cost to see the same symbols.
     */
    private final Map<Integer, ImageIcon> symbolCache = new HashMap<>();
    private static final int SYMBOL_SIZE = 44;

    /** Votes durably recorded at this terminal today — counted the moment a vote is
     *  written to encrypted local storage, since that is the point this terminal itself
     *  guarantees it will not be lost, independent of whether server delivery is
     *  immediate or queued. Reset only by restarting the terminal. */
    private int votesRecordedHere = 0;

    public static void main(String[] args) {
        MachineSettings settings;
        try {
            settings = MachineSettings.load();
        } catch (RuntimeException e) {
            System.err.println("Configuration problem: " + e.getMessage());
            JOptionPane.showMessageDialog(null, e.getMessage(),
                    "Cannot start terminal", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Theme.install();
        MachineSettings finalSettings = settings;
        SwingUtilities.invokeLater(() -> new VotingMachineApp(finalSettings).start());
    }

    private VotingMachineApp(MachineSettings settings) {
        this.settings = settings;
        this.server   = new ServerClient(settings);
        this.queue    = new VoteQueue(settings);
        this.frame    = new KioskFrame(this);

        this.syncWorker = new VoteSyncWorker(server, queue, settings.queueRetrySeconds(),
                status -> SwingUtilities.invokeLater(() -> frame.setQueueStatus(
                        status.pendingCount() == 0
                                ? "all delivered"
                                : status.pendingCount() + " held — " + status.message(),
                        status.allDelivered())));

        this.countdownTimer = new Timer(1000, event -> tickCountdown());
        this.countdownTimer.setRepeats(true);
    }

    private void start() {
        frame.setTerminalStatus(settings.machineId(), null);
        frame.setHardwareStatus(cardReader.isSimulated() ? "simulated card + fingerprint" : "connected");
        frame.setQueueStatus(queue.isEmpty() ? "all delivered" : queue.size() + " held", queue.isEmpty());
        frame.show(KioskFrame.Screen.BOOT);

        if (settings.kioskFullScreen()) {
            frame.enterKioskMode();
        }
        frame.setVisible(true);

        syncWorker.start();
        connect();
    }

    // ── Boot and connection ─────────────────────────────────────────────────

    private void connect() {
        frame.setBootMessage("Registering this terminal with the server…", false);

        worker.submit(() -> {
            try {
                ApiResponses.Registration registration = server.register();
                if (!registration.success()) {
                    onEdt(() -> frame.setBootMessage(
                            "Registration refused: " + registration.message(), true));
                    return;
                }
                ApiResponses.ElectionStatus status = server.electionStatus();
                onEdt(() -> {
                    frame.setTerminalStatus(registration.machineId(), registration.boothName());
                    applyElectionStatus(status);
                });
            } catch (ServerUnavailableException e) {
                onEdt(() -> frame.setBootMessage(unreachableMessage(e), true));
            } catch (Exception e) {
                onEdt(() -> frame.setBootMessage("Startup failed: " + e.getMessage(), true));
            }
        });
    }

    private String unreachableMessage(Exception cause) {
        String held = queue.isEmpty()
                ? ""
                : " " + queue.size() + " vote(s) are held safely on this terminal and will be "
                        + "delivered automatically once the server is back.";
        return "Cannot reach the server. " + cause.getMessage() + held;
    }

    private void applyElectionStatus(ApiResponses.ElectionStatus status) {
        if (status.isActive()) {
            activeElectionId   = status.electionId();
            activeElectionName = status.electionName();
            frame.setElectionStatus(status.electionName() + " (" + status.electionType() + ")");
            frame.setIdleError(null);
            frame.show(KioskFrame.Screen.IDLE);
        } else {
            activeElectionId = null;
            frame.setElectionStatus("closed");
            frame.setClosedMessage(status.message() == null
                    ? "Waiting for the election officer to open polling."
                    : status.message());
            frame.show(KioskFrame.Screen.CLOSED);
        }
    }

    @Override
    public void onRetryConnection() {
        if (frame.currentScreen() == KioskFrame.Screen.BOOT) {
            connect();
        } else if (frame.currentScreen() == KioskFrame.Screen.CLOSED) {
            refreshElectionStatus();
        }
    }

    private void refreshElectionStatus() {
        worker.submit(() -> {
            try {
                ApiResponses.ElectionStatus status = server.electionStatus();
                onEdt(() -> applyElectionStatus(status));
            } catch (Exception e) {
                onEdt(() -> frame.setClosedMessage("Could not reach the server: " + e.getMessage()));
            }
        });
    }

    // ── Step 1: card ────────────────────────────────────────────────────────

    @Override
    public void onCardPresented(String cardIdentifier) {
        cardReader.presentCard(cardIdentifier);
        frame.setIdleError("Reading card…");

        worker.submit(() -> {
            try {
                String identifier = cardReader.readCardIdentifier();
                ApiResponses.CardResult card = server.verifyCard(identifier);

                if (!card.success()) {
                    onEdt(() -> resetToIdle(card.message()));
                    return;
                }
                if (card.hasVoted()) {
                    onEdt(() -> resetToIdle(card.message()));
                    return;
                }

                // The card is treated as carrying the fingerprint reference, so the voter
                // needs only to place a finger — no second identifier to type in.
                scanner.loadSampleFromCard(card.simulatedFingerprintCode());

                ApiResponses.VoterDetails details = server.voterDetails(card.voterId());
                onEdt(() -> {
                    voterId   = card.voterId();
                    voterName = card.voterName();
                    showIdentity(details, card);
                });
            } catch (HardwareException e) {
                onEdt(() -> resetToIdle(e.getMessage()));
            } catch (ServerUnavailableException e) {
                onEdt(() -> resetToIdle("Server unreachable. Ask the booth officer to wait a moment."));
            } catch (Exception e) {
                onEdt(() -> resetToIdle("Could not read the card: " + e.getMessage()));
            }
        });
    }

    private void showIdentity(ApiResponses.VoterDetails details, ApiResponses.CardResult card) {
        String constituency = "Constituency on file";
        if (details.success()) {
            String ls = details.lsConstituencyName();
            String vs = details.constituencyName();
            if (ls != null && vs != null) {
                constituency = "Lok Sabha: " + ls + "   •   Vidhan Sabha: " + vs;
            } else if (vs != null) {
                constituency = "Vidhan Sabha: " + vs;
            } else if (ls != null) {
                constituency = "Lok Sabha: " + ls;
            }
        }

        String warning = "";
        if (!scanner.hasSample() && scanner.isSimulated()) {
            warning = "This card carries no fingerprint reference. Ask the booth officer for help.";
        }
        frame.showIdentity(
                card.voterName(),
                card.voterId(),
                constituency,
                details.success() ? details.photoBase64() : null,
                warning);
        frame.show(KioskFrame.Screen.IDENTITY);
    }

    // ── Step 2: identity confirmation ───────────────────────────────────────

    @Override
    public void onIdentityConfirmed() {
        frame.setFingerprintState("Place your finger on the scanner",
                scanner.isSimulated() ? scanner.description() : "");
        frame.show(KioskFrame.Screen.FINGERPRINT);
    }

    // ── Step 3: fingerprint ─────────────────────────────────────────────────

    @Override
    public void onFingerPlaced() {
        if (voterId == null) {
            resetToIdle("Session lost. Please place your card again.");
            return;
        }
        scanner.placeFinger();
        frame.setFingerprintState("Verifying fingerprint…", "");

        worker.submit(() -> {
            try {
                String sample = scanner.captureSample();
                ApiResponses.FingerprintResult result = server.verifyFingerprint(voterId, sample);

                if (!result.match()) {
                    onEdt(() -> frame.setFingerprintState(
                            "Fingerprint did not match",
                            result.message() == null ? "Please try again." : result.message()));
                    return;
                }
                biometricToken = result.biometricToken();

                ApiResponses.SessionResult started = server.startSession(voterId, biometricToken);
                if (started.success()) {
                    fetchSymbols(started.candidates());
                }
                onEdt(() -> openBallot(started));

            } catch (HardwareException e) {
                onEdt(() -> frame.setFingerprintState("Scan failed", e.getMessage()));
            } catch (ServerUnavailableException e) {
                onEdt(() -> resetToIdle("Server unreachable. Please wait and try again."));
            } catch (Exception e) {
                onEdt(() -> resetToIdle("Verification failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Fetches and decodes any candidate symbols not already cached from a previous voter.
     * Called on the worker thread, never the EDT — network I/O and image decoding both
     * belong off the event thread.
     */
    private void fetchSymbols(List<ApiResponses.CandidateOption> candidates) {
        for (ApiResponses.CandidateOption candidate : candidates) {
            if (!candidate.hasSymbol() || symbolCache.containsKey(candidate.id())) {
                continue;
            }
            try {
                byte[] bytes = server.candidateSymbol(candidate.id());
                if (bytes != null) {
                    Image scaled = new ImageIcon(bytes).getImage()
                            .getScaledInstance(SYMBOL_SIZE, SYMBOL_SIZE, Image.SCALE_SMOOTH);
                    symbolCache.put(candidate.id(), new ImageIcon(scaled));
                }
            } catch (IOException | RuntimeException e) {
                // A missing symbol is cosmetic, not a reason to block the ballot. The row
                // falls back to a plain layout without one.
            }
        }
    }

    private void openBallot(ApiResponses.SessionResult started) {
        if (!started.success()) {
            resetToIdle(started.message());
            return;
        }
        session         = started;
        chosenCandidate = null;

        frame.showBallot(started.voterName(), started.constituencyName(), started.candidates(), symbolCache);
        startCountdown((int) Math.min(started.expiresInSeconds(), settings.sessionTimeoutSeconds()));
        frame.show(KioskFrame.Screen.BALLOT);
    }

    // ── Step 4: choose and confirm ──────────────────────────────────────────

    @Override
    public void onCandidateSelected(int slotNumber) {
        if (session == null) {
            resetToIdle("Session expired. Please start again.");
            return;
        }
        Optional<ApiResponses.CandidateOption> candidate = frame.ballotCandidates().stream()
                .filter(option -> option.slotNumber() == slotNumber)
                .findFirst();

        if (candidate.isEmpty()) {
            // A button with no candidate behind it. Ignored rather than treated as an error,
            // because on the real enclosure all eight buttons exist whatever the ballot holds.
            return;
        }
        chosenCandidate = candidate.get();
        frame.showConfirmation(chosenCandidate, symbolCache);
        frame.show(KioskFrame.Screen.CONFIRM);
    }

    /**
     * Commits the vote.
     *
     * <p>Order matters here. The vote is written to the encrypted local queue <em>before</em>
     * any network call and before the voter is told anything. Only then is delivery attempted.
     * If the server is unreachable the voter still sees a confirmed vote, because the vote
     * genuinely is recorded — on the terminal, with an idempotency key, awaiting delivery.
     * The previous client did the opposite: it called the server first and, on failure, showed
     * an error and dropped the vote entirely.
     */
    @Override
    public void onConfirmVote() {
        if (session == null || chosenCandidate == null) {
            resetToIdle("Nothing to confirm. Please start again.");
            return;
        }
        stopCountdown();

        PendingVote vote = PendingVote.create(
                session.sessionToken(),
                chosenCandidate.id(),
                chosenCandidate.name(),
                session.electionName());

        try {
            queue.enqueue(vote);
            votesRecordedHere++;
        } catch (IOException e) {
            // Durability is the one thing that cannot be compromised. Refuse rather than
            // accept a vote that might vanish.
            frame.showMessage("Cannot record vote",
                    "This terminal could not save your vote to local storage, so it has not "
                            + "been accepted. Please inform the booth officer.\n\n" + e.getMessage(),
                    true);
            return;
        }

        frame.showSuccess("Your vote has been recorded", "Confirming with the server…", "", "");
        frame.show(KioskFrame.Screen.SUCCESS);
        clearVoterState();

        worker.submit(() -> {
            try {
                ApiResponses.VoteReceipt receipt = server.castVote(vote);
                if (receipt.success()) {
                    queue.remove(vote.idempotencyKey());
                    onEdt(() -> frame.showSuccess(
                            "Your vote has been recorded",
                            "Receipt  " + receipt.receipt(),
                            receipt.blockNumber() == null
                                    ? "Committed to the election ledger."
                                    : "Ledger block #" + receipt.blockNumber(),
                            ""));
                } else {
                    queue.remove(vote.idempotencyKey());
                    onEdt(() -> frame.showSuccess(
                            "Vote not accepted",
                            "—",
                            receipt.message() == null ? "The server declined this vote." : receipt.message(),
                            "Please inform the booth officer."));
                }
            } catch (ServerUnavailableException e) {
                syncWorker.nudge();
                onEdt(() -> frame.showSuccess(
                        "Your vote has been recorded",
                        "Held on this terminal",
                        "It will be delivered to the server automatically.",
                        "No action needed. Your vote is safe."));
            } catch (Exception e) {
                syncWorker.nudge();
                onEdt(() -> frame.showSuccess(
                        "Your vote has been recorded",
                        "Held on this terminal",
                        "Delivery will be retried automatically.",
                        "No action needed. Your vote is safe."));
            }
        });
    }

    // ── Cancel, timeout, reset ──────────────────────────────────────────────

    @Override
    public void onCancel() {
        String token = session == null ? null : session.sessionToken();
        if (token != null) {
            worker.submit(() -> {
                try {
                    server.cancelSession(token);
                } catch (Exception e) {
                    System.err.println("Could not cancel the session on the server: " + e.getMessage());
                }
            });
        }
        resetToIdle("Cancelled. Next voter may place their card.");
    }

    private void startCountdown(int seconds) {
        secondsRemaining = Math.max(1, seconds);
        frame.setCountdown(secondsRemaining);
        countdownTimer.restart();
    }

    private void stopCountdown() {
        countdownTimer.stop();
        frame.setCountdown(0);
    }

    private void tickCountdown() {
        secondsRemaining--;
        frame.setCountdown(Math.max(0, secondsRemaining));
        if (secondsRemaining > 0) {
            return;
        }
        stopCountdown();

        String token = session == null ? null : session.sessionToken();
        if (token != null) {
            worker.submit(() -> {
                try {
                    server.timeoutSession(token);
                } catch (Exception e) {
                    System.err.println("Could not report the timeout: " + e.getMessage());
                }
            });
        }
        resetToIdle("Timed out. Please place your card again to restart.");
    }

    @Override
    public void onReturnToIdle() {
        resetToIdle(null);
    }

    private void resetToIdle(String message) {
        stopCountdown();
        clearVoterState();
        cardReader.removeCard();
        scanner.clear();

        if (activeElectionId == null) {
            frame.setClosedMessage(message == null
                    ? "Waiting for the election officer to open polling."
                    : message);
            frame.show(KioskFrame.Screen.CLOSED);
            return;
        }
        frame.setIdleError(message);
        frame.show(KioskFrame.Screen.IDLE);
    }

    private void clearVoterState() {
        voterId         = null;
        voterName       = null;
        biometricToken  = null;
        session         = null;
        chosenCandidate = null;
    }

    // ── Officer panel ───────────────────────────────────────────────────────

    @Override
    public void onOfficerPanelRequested() {
        if (settings.adminKey() == null) {
            frame.showMessage("Officer panel unavailable",
                    "ADMIN_KEY is not configured on this terminal, so officer actions are "
                            + "disabled here. Use the server's admin dashboard instead.", false);
            return;
        }
        if (session != null) {
            frame.showMessage("Voting in progress",
                    "Finish or cancel the current voter before opening the officer panel.", false);
            return;
        }
        frame.clearOfficerOutput();
        frame.setOfficerElection("Active election: " + activeElectionName);
        frame.show(KioskFrame.Screen.OFFICER);
        onRefreshTurnout();
    }

    @Override
    public void onOpenElection() {
        chooseElection("Open polling for which election?").ifPresent(electionId ->
                worker.submit(() -> {
                    try {
                        ApiResponses.SimpleResult result = server.openElection(electionId);
                        onEdt(() -> {
                            frame.appendOfficerOutput(result.message());
                            refreshElectionStatusQuietly();
                        });
                    } catch (Exception e) {
                        onEdt(() -> frame.appendOfficerOutput("Failed: " + e.getMessage()));
                    }
                }));
    }

    @Override
    public void onCloseElection() {
        chooseElection("Close polling for which election?").ifPresent(electionId ->
                worker.submit(() -> {
                    try {
                        ApiResponses.SimpleResult result = server.closeElection(electionId);
                        onEdt(() -> {
                            frame.appendOfficerOutput(result.message());
                            refreshElectionStatusQuietly();
                        });
                    } catch (Exception e) {
                        onEdt(() -> frame.appendOfficerOutput("Failed: " + e.getMessage()));
                    }
                }));
    }

    /**
     * Asks the officer which election to act on.
     *
     * <p>Blocking dialog on the event thread on purpose: it is an officer action outside the
     * voting flow, and no voter is waiting.
     */
    @SuppressWarnings("unchecked")
    private Optional<Integer> chooseElection(String prompt) {
        try {
            Map<String, Object> response = server.adminElections();
            Object raw = response.get("elections");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                frame.appendOfficerOutput("The server returned no elections.");
                return Optional.empty();
            }
            List<String> labels = new ArrayList<>();
            List<Integer> ids   = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> election) {
                    Object id = election.get("id");
                    if (id == null) {
                        continue;
                    }
                    ids.add(((Number) id).intValue());
                    labels.add(election.get("name") + "  [" + election.get("type") + "]"
                            + (Boolean.TRUE.equals(election.get("isActive")) ? "  — open" : ""));
                }
            }
            if (ids.isEmpty()) {
                frame.appendOfficerOutput("The server returned no usable elections.");
                return Optional.empty();
            }
            Object choice = JOptionPane.showInputDialog(frame, prompt, "Election officer",
                    JOptionPane.QUESTION_MESSAGE, null, labels.toArray(), labels.get(0));
            int index = labels.indexOf(choice);
            return index < 0 ? Optional.empty() : Optional.of(ids.get(index));

        } catch (Exception e) {
            frame.appendOfficerOutput("Could not load elections: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void onRefreshTurnout() {
        worker.submit(() -> {
            try {
                Map<String, Object> turnout = server.turnout();
                onEdt(() -> {
                    frame.appendOfficerOutput("── Turnout ──");
                    turnout.forEach((key, value) -> frame.appendOfficerOutput("  " + key + ": " + value));
                    frame.appendOfficerOutput("── This terminal ──");
                    frame.appendOfficerOutput("  votes recorded here today: " + votesRecordedHere);
                    frame.appendOfficerOutput("  held locally, awaiting delivery: " + queue.size());
                });
            } catch (Exception e) {
                onEdt(() -> frame.appendOfficerOutput("Could not read turnout: " + e.getMessage()));
            }
        });
    }

    @Override
    public void onSyncNow() {
        frame.appendOfficerOutput("Requesting immediate delivery of "
                + queue.size() + " held vote(s)…");
        syncWorker.nudge();
    }

    private void refreshElectionStatusQuietly() {
        worker.submit(() -> {
            try {
                ApiResponses.ElectionStatus status = server.electionStatus();
                onEdt(() -> {
                    if (status.isActive()) {
                        activeElectionId   = status.electionId();
                        activeElectionName = status.electionName();
                        frame.setElectionStatus(status.electionName() + " (" + status.electionType() + ")");
                    } else {
                        activeElectionId = null;
                        frame.setElectionStatus("closed");
                    }
                    frame.setOfficerElection("Active election: "
                            + (activeElectionId == null ? "none" : activeElectionName));
                });
            } catch (Exception e) {
                System.err.println("Could not refresh election status: " + e.getMessage());
            }
        });
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    private static void onEdt(Runnable action) {
        SwingUtilities.invokeLater(action);
    }
}

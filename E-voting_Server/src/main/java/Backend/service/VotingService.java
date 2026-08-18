package Backend.service;

import Backend.dto.*;
import Backend.ledger.LedgerBlockRepository;
import Backend.model.*;
import Backend.repository.*;
import Backend.security.MachineSecurityProperties;
import Backend.security.VotePayloadCodec.SealedVote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The voter journey: card read, fingerprint, session, ballot, cast.
 *
 * <p>Only PM (Lok Sabha) and CM (Vidhan Sabha) elections are supported. The former
 * four-tier municipality path has been removed.
 */
@Service
public class VotingService {

    /**
     * Candidate slots the terminal can display. The quotation specifies a non-touch
     * 7" display with eight illuminated candidate buttons plus Confirm and Cancel, so
     * a ballot cannot offer more choices than there are buttons to press.
     */
    public static final int MAX_BALLOT_SLOTS = 8;

    private static final String ELECTION_PM = "PM";
    private static final String ELECTION_CM = "CM";

    private static final Logger log = LoggerFactory.getLogger(VotingService.class);

    private final VoterRepository          voters;
    private final VotingSessionRepository  sessions;
    private final CandidateRepository      candidates;
    private final ElectionRepository       elections;
    private final VoterTurnoutRepository   turnout;
    private final BallotRepository         ballots;
    private final LedgerBlockRepository    blocks;
    private final BiometricService         biometrics;
    private final VoteRecorder             recorder;
    private final MachineSecurityProperties properties;
    private final JdbcTemplate              jdbc;

    public VotingService(VoterRepository voters,
                         VotingSessionRepository sessions,
                         CandidateRepository candidates,
                         ElectionRepository elections,
                         VoterTurnoutRepository turnout,
                         BallotRepository ballots,
                         LedgerBlockRepository blocks,
                         BiometricService biometrics,
                         VoteRecorder recorder,
                         MachineSecurityProperties properties,
                         JdbcTemplate jdbc) {
        this.voters     = voters;
        this.sessions   = sessions;
        this.candidates = candidates;
        this.elections  = elections;
        this.turnout    = turnout;
        this.ballots    = ballots;
        this.blocks     = blocks;
        this.biometrics = biometrics;
        this.recorder   = recorder;
        this.properties = properties;
        this.jdbc       = jdbc;
    }

    // ── Election state ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<Election> activeElection() {
        return elections.findByIsActiveTrue()
                .filter(election -> isSupported(election.getType()));
    }

    private static boolean isSupported(String electionType) {
        return ELECTION_PM.equals(electionType) || ELECTION_CM.equals(electionType);
    }

    // ── Step 1: card read ───────────────────────────────────────────────────

    /**
     * Resolves a card identifier to a voter.
     *
     * <p>Accepts either the NFC card UID or the voter ID, which is what makes the
     * simulated reader work: typing the identifier stands in for tapping the card.
     */
    @Transactional
    public VoterCardResult verifyCard(String cardIdentifier, String machineId) {
        if (cardIdentifier == null || cardIdentifier.isBlank()) {
            return VoterCardResult.fail("No card detected. Please place the card on the reader.");
        }
        Optional<Election> election = activeElection();
        if (election.isEmpty()) {
            return VoterCardResult.fail("Voting is not open. Contact the election officer.");
        }

        Voter voter = findVoter(cardIdentifier.trim());
        if (voter == null) {
            log.info("Unrecognised card presented at terminal {}.", machineId);
            return VoterCardResult.fail("Card not recognised. Please contact the booth officer.");
        }
        if (Boolean.FALSE.equals(voter.getCardActive())) {
            return VoterCardResult.fail("This card is deactivated. Please contact the booth officer.");
        }

        boolean alreadyVoted = turnout.existsByVoterIdAndElectionId(
                voter.getVoterId(), election.get().getId());

        // Give simulated voters a real enrolled template so the fingerprint check that
        // follows exercises the genuine verification path rather than being waved through.
        biometrics.ensureEnrolled(voter);

        String simulatedCode = biometrics.simulationEnabled()
                ? biometrics.simulatedSampleCode(voter.getVoterId())
                : null;

        return new VoterCardResult(
                true,
                alreadyVoted
                        ? "This voter has already voted in " + election.get().getName() + "."
                        : "Card verified. Please place your finger on the scanner.",
                voter.getVoterId(),
                voter.getName(),
                alreadyVoted,
                simulatedCode);
    }

    @Transactional(readOnly = true)
    public Optional<Voter> lookupVoter(String voterId) {
        return Optional.ofNullable(voters.findByVoterId(voterId));
    }

    @Transactional(readOnly = true)
    public boolean hasVoted(String voterId, Integer electionId) {
        return turnout.existsByVoterIdAndElectionId(voterId, electionId);
    }

    // ── Step 2: fingerprint ─────────────────────────────────────────────────

    /**
     * Verifies a fingerprint sample and, on success, mints the single-use token that
     * session creation requires.
     */
    @Transactional
    public FingerprintResult verifyFingerprint(String voterId, String sample, String machineId) {
        Voter voter = voters.findByVoterId(voterId);
        if (voter == null) {
            return FingerprintResult.fail("Voter not found.");
        }
        if (!biometrics.verify(voter, sample)) {
            log.info("Fingerprint mismatch for voter {} at terminal {}.", voterId, machineId);
            return FingerprintResult.mismatch(voterId);
        }
        String token = biometrics.issueVerificationToken(voter.getVoterId(), machineId);
        return new FingerprintResult(
                true, true, "Fingerprint verified.", voter.getVoterId(), token,
                properties.getBiometricTokenTtl().toSeconds());
    }

    // ── Step 3: session ─────────────────────────────────────────────────────

    /**
     * Opens a voting session, but only against a valid biometric token.
     *
     * <p>This is the gate that was missing: the endpoint used to mark the session
     * biometrically verified itself, so a caller could go straight from card read to
     * cast vote.
     */
    @Transactional
    public SessionResult startSession(String voterId, String biometricToken, String machineId) {
        Optional<Election> maybeElection = activeElection();
        if (maybeElection.isEmpty()) {
            return SessionResult.fail("Voting is not open. Contact the election officer.");
        }
        Election election = maybeElection.get();

        Voter voter = voters.findByVoterId(voterId);
        if (voter == null) {
            return SessionResult.fail("Voter not found.");
        }
        if (Boolean.FALSE.equals(voter.getCardActive())) {
            return SessionResult.fail("This card is deactivated. Please contact the booth officer.");
        }
        if (turnout.existsByVoterIdAndElectionId(voter.getVoterId(), election.getId())) {
            return SessionResult.fail("You have already voted in " + election.getName() + ".");
        }
        if ("CM".equals(election.getType()) && !stateIsOpenForCm(election.getId(), voter.getStateId())) {
            return SessionResult.fail(
                    "Voting has not opened yet for your state in this election. Contact the booth officer.");
        }
        if (biometrics.consume(biometricToken, voter.getVoterId(), machineId).isEmpty()) {
            return SessionResult.fail(
                    "Biometric verification is required, has expired, or was already used. "
                            + "Please scan your fingerprint again.");
        }

        Integer constituencyId = constituencyFor(election, voter);
        if (constituencyId == null) {
            return SessionResult.fail(
                    "You are not assigned to a constituency for this election. Contact the booth officer.");
        }

        ensureNota(election.getId(), constituencyId, voter.getStateId());
        List<CandidateOption> ballot = ballotFor(election, constituencyId);
        if (ballot.isEmpty()) {
            return SessionResult.fail("No candidates are registered for your constituency.");
        }

        VotingSession session = new VotingSession(
                voter.getVoterId(),
                UUID.randomUUID().toString(),
                election.getId(),
                machineId,
                LocalDateTime.now().plus(properties.getVotingSessionTtl()));
        session.confirmBiometrics();
        sessions.save(session);

        return new SessionResult(
                true, "Session started.",
                session.getSessionToken(),
                voter.getName(),
                election.getName(),
                election.getType(),
                constituencyId,
                constituencyName(constituencyId),
                session.secondsRemaining(),
                ballot);
    }

    /** The constituency's real name, so a voter-facing screen shows "Dindigul" rather than
     *  a bare numeric id the voter has no way to verify against their card. */
    public String constituencyName(Integer constituencyId) {
        if (constituencyId == null) {
            return null;
        }
        try {
            return jdbc.queryForObject(
                    "SELECT name FROM constituencies WHERE id = ?", String.class, constituencyId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Guarantees NOTA exists on this constituency's ballot before it is built.
     *
     * <p>{@link Backend.controller.DataAdminController#addCandidate} does the same thing
     * when a real candidate is registered, so most constituencies already have NOTA long
     * before anyone votes there. This is the backstop for the rest: a constituency with no
     * real candidates registered at all still gets a NOTA-only ballot instead of turning
     * the voter away with "no candidates registered," which was never a real answer a
     * voter could act on.
     *
     * <p>Deliberately called inline from {@link #startSession}, in the same transaction,
     * rather than as its own {@code @Transactional} method — {@code @Transactional} is
     * proxy-based, and a self-invocation between two methods on this class would bypass
     * the proxy and silently not get its own transaction boundary anyway.
     */
    private void ensureNota(Integer electionId, Integer constituencyId, Integer stateId) {
        boolean hasNota = candidates.findByElectionIdAndConstituencyId(electionId, constituencyId)
                .stream().anyMatch(c -> "NOTA".equalsIgnoreCase(c.getName()));
        if (hasNota) {
            return;
        }
        Candidate nota = new Candidate();
        nota.setName("NOTA");
        nota.setParty("None of the Above");
        nota.setElectionId(electionId);
        nota.setConstituencyId(constituencyId);
        nota.setStateId(stateId);
        try {
            candidates.save(nota);
        } catch (RuntimeException e) {
            // Lost a race with another voter's session starting for the same constituency
            // at the same moment; whichever save landed first already satisfies this.
            log.debug("NOTA insert skipped for election {} constituency {}: {}",
                    electionId, constituencyId, e.getMessage());
        }
    }

    /** Whether this CM election has been explicitly opened to this voter's state — see
     *  {@code V8__cm_election_state_gating.sql}. A voter with no state on file is never
     *  gated open, since there is no state to check against. */
    private boolean stateIsOpenForCm(Integer electionId, Integer stateId) {
        if (stateId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM election_open_states WHERE election_id = ? AND state_id = ?",
                Integer.class, electionId, stateId);
        return count != null && count > 0;
    }

    /**
     * Which constituency's ballot this voter receives.
     *
     * <p>Deliberately derived from the voter's registration, never from the terminal's
     * location. That is what lets a voter use any booth: the machine has no say in
     * which ballot appears.
     */
    private Integer constituencyFor(Election election, Voter voter) {
        return ELECTION_PM.equals(election.getType())
                ? voter.getLsConstituencyId()
                : voter.getVsConstituencyId();
    }

    @Transactional(readOnly = true)
    public List<CandidateOption> ballotFor(Election election, Integer constituencyId) {
        List<Candidate> found = candidates.findByElectionIdAndConstituencyId(
                election.getId(), constituencyId);

        if (found.size() > MAX_BALLOT_SLOTS) {
            log.warn("Constituency {} has {} candidates but the terminal has only {} buttons. "
                            + "Showing the first {}.",
                    constituencyId, found.size(), MAX_BALLOT_SLOTS, MAX_BALLOT_SLOTS);
        }

        List<CandidateOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(found.size(), MAX_BALLOT_SLOTS); i++) {
            Candidate candidate = found.get(i);
            options.add(new CandidateOption(
                    candidate.getId(),
                    candidate.getName(),
                    candidate.getNameTa(),
                    candidate.getParty(),
                    candidate.getPartyTa(),
                    i + 1,
                    candidate.getPhotoData() != null,
                    candidate.getSymbolData() != null));
        }
        return options;
    }

    // ── Step 4: cast ────────────────────────────────────────────────────────

    /**
     * Records a vote, tolerating repeat delivery.
     *
     * <p>Deliberately not annotated {@code @Transactional}. The one-vote-per-voter rule
     * is enforced by a unique constraint, and a constraint violation caught inside the
     * transaction that raised it would leave that transaction rollback-only. Orchestrating
     * from outside the boundary lets a duplicate be answered properly — which matters
     * because a terminal replaying a queued vote after a network failure is normal
     * operation, not an error.
     */
    public VoteReceipt castVote(String machineId, SealedVote sealed) {
        // A vote this terminal already delivered. Answer with the original receipt so
        // the queue can drop the entry instead of retrying forever.
        Optional<VoteReceipt> alreadyRecorded = receiptForIdempotencyKey(sealed.idempotencyKey());
        if (alreadyRecorded.isPresent()) {
            log.info("Idempotent replay of vote {} from terminal {}.",
                    sealed.idempotencyKey(), machineId);
            return alreadyRecorded.get();
        }

        VotingSession session = sessions.findBySessionToken(sealed.sessionToken());
        if (session == null) {
            return VoteReceipt.fail("Session not found. Please start again.");
        }
        if (session.isSpent()) {
            return VoteReceipt.fail("This session has already been used.");
        }
        if (!session.isBiometricallyVerified()) {
            return VoteReceipt.fail("Biometric verification is incomplete for this session.");
        }
        if (!Objects.equals(session.getMachineId(), machineId)) {
            log.warn("Terminal {} tried to cast a vote for a session opened on {}.",
                    machineId, session.getMachineId());
            return VoteReceipt.fail("This session belongs to a different terminal.");
        }

        VoteReceipt timingProblem = checkCastTiming(session, sealed);
        if (timingProblem != null) {
            return timingProblem;
        }

        Election election = elections.findById(session.getElectionId()).orElse(null);
        if (election == null || !isSupported(election.getType())) {
            return VoteReceipt.fail("Election is not available.");
        }
        // Polling may have closed while this vote sat in the terminal's queue. A ballot
        // cast before the close still counts, which is why the session window above is
        // the authority here rather than the election's current state.
        if (!Boolean.TRUE.equals(election.getIsActive())) {
            log.info("Accepting a queued vote for closed election '{}' cast at {}.",
                    election.getName(), java.time.Instant.ofEpochMilli(sealed.effectiveCastAt()));
        }

        Voter voter = voters.findByVoterId(session.getVoterId());
        if (voter == null) {
            return VoteReceipt.fail("Voter not found.");
        }

        Candidate candidate = candidates.findById(sealed.candidateId()).orElse(null);
        if (candidate == null) {
            return VoteReceipt.fail("Candidate not found.");
        }
        if (!Objects.equals(candidate.getElectionId(), election.getId())) {
            return VoteReceipt.fail("That candidate is not standing in this election.");
        }

        Integer constituencyId = constituencyFor(election, voter);
        if (!Objects.equals(candidate.getConstituencyId(), constituencyId)) {
            log.warn("Terminal {} submitted candidate {} outside the voter's constituency {}.",
                    machineId, candidate.getId(), constituencyId);
            return VoteReceipt.fail("That candidate is not standing in your constituency.");
        }
        if (turnout.existsByVoterIdAndElectionId(voter.getVoterId(), election.getId())) {
            return VoteReceipt.fail("You have already voted in " + election.getName() + ".");
        }

        try {
            VoteRecorder.RecordedVote recorded = recorder.record(
                    session,
                    candidate.getId(),
                    election.getType(),
                    constituencyId,
                    machineId,
                    sealed.idempotencyKey());

            return new VoteReceipt(
                    true,
                    "Vote recorded and committed to the ledger.",
                    recorded.ballot().getBallotUuid(),
                    recorded.block().getBlockIndex(),
                    recorded.block().getHash(),
                    election.getName(),
                    false);

        } catch (DataIntegrityViolationException e) {
            // Lost a race. Either the same vote arrived twice, or this voter voted at
            // another booth in the meantime. The database, not this code, is the arbiter.
            return receiptForIdempotencyKey(sealed.idempotencyKey())
                    .orElseGet(() -> {
                        log.info("Concurrent duplicate vote rejected for voter {} in election {}.",
                                session.getVoterId(), session.getElectionId());
                        return VoteReceipt.fail(
                                "You have already voted in " + election.getName() + ".");
                    });
        }
    }

    /**
     * Validates when the vote was cast rather than when it arrived.
     *
     * <p>This is what makes the terminal's store-and-forward queue safe. A vote is
     * accepted if the voter pressed Confirm inside their session's window, however long
     * the terminal then took to deliver it. Checking the arrival time instead would mean a
     * vote held through a two-minute network outage was rejected as "session timed out"
     * and quietly lost — the exact failure the queue exists to prevent.
     *
     * @return null when the timing is acceptable, otherwise the failure to return
     */
    private VoteReceipt checkCastTiming(VotingSession session, SealedVote sealed) {
        LocalDateTime castAt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(sealed.effectiveCastAt()),
                java.time.ZoneId.systemDefault());

        // A small allowance for clock drift on a Pi with no battery-backed clock.
        LocalDateTime windowOpens  = session.getCreatedAt().minusSeconds(30);
        LocalDateTime windowCloses = session.getExpiresAt().plusSeconds(30);

        if (castAt.isBefore(windowOpens) || castAt.isAfter(windowCloses)) {
            log.warn("Vote for session {} claims a cast time of {}, outside its window {} to {}.",
                    session.getId(), castAt, windowOpens, windowCloses);
            return VoteReceipt.fail("Session timed out before the vote was confirmed. Please start again.");
        }

        LocalDateTime staleAfter = castAt.plus(properties.getQueuedVoteMaxAge());
        if (staleAfter.isBefore(LocalDateTime.now())) {
            log.error("Discarding a queued vote cast at {}, older than the {} delivery limit.",
                    castAt, properties.getQueuedVoteMaxAge());
            return VoteReceipt.fail("This vote is too old to be accepted. Contact the election officer.");
        }
        return null;
    }

    /** Rebuilds the original receipt for a vote that was already recorded. */
    @Transactional(readOnly = true)
    public Optional<VoteReceipt> receiptForIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return ballots.findByIdempotencyKey(idempotencyKey).map(ballot -> {
            String electionName = elections.findById(ballot.getElectionId())
                    .map(Election::getName)
                    .orElse("Election");
            return blocks.findByBallotUuid(ballot.getBallotUuid())
                    .map(block -> new VoteReceipt(true,
                            "This vote was already recorded.",
                            ballot.getBallotUuid(),
                            block.getBlockIndex(),
                            block.getHash(),
                            electionName,
                            true))
                    .orElseGet(() -> new VoteReceipt(true,
                            "This vote was already recorded.",
                            ballot.getBallotUuid(), null, null, electionName, true));
        });
    }

    // ── Session teardown ────────────────────────────────────────────────────

    /**
     * Ends a session without recording a vote, for Cancel and for the idle timeout.
     *
     * <p>Marks it spent rather than deleting it, so the abandoned session is still
     * visible in an audit.
     */
    @Transactional
    public SimpleResult closeSession(String sessionToken, String reason) {
        VotingSession session = sessions.findBySessionToken(sessionToken);
        if (session == null) {
            return SimpleResult.ok("Session already closed.");
        }
        if (!session.isSpent()) {
            session.setUsed(true);
            sessions.save(session);
        }
        log.info("Session for voter {} closed: {}", session.getVoterId(), reason);
        return SimpleResult.ok(reason);
    }

    private Voter findVoter(String identifier) {
        Voter voter = voters.findByNfcCardId(identifier);
        return voter != null ? voter : voters.findByVoterId(identifier);
    }
}

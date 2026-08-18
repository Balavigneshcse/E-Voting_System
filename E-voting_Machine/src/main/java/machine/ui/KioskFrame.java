package machine.ui;

import machine.api.ApiResponses.CandidateOption;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The voter-facing screen.
 *
 * <p>Purely a view: it renders state and reports button presses to a {@link Listener}. All
 * decisions live in the controller, which is what allows the whole flow to be driven either
 * by keyboard now or by GPIO later without touching this class.
 *
 * <h2>Ten buttons, no touchscreen</h2>
 * The quotation specifies a non-touch 7" display with ten illuminated buttons: eight
 * candidate slots plus Confirm and Cancel. Every interaction here maps onto exactly those
 * ten inputs, with keys 1–8, Enter and Escape standing in for them. On-screen controls are
 * clickable too, which makes a laptop demo possible, but nothing depends on a pointer — so
 * the same code drives the real enclosure once the buttons are wired to GPIO.
 */
public class KioskFrame extends JFrame {

    /** Physical button count for candidates, matching the enclosure's eight cutouts. */
    public static final int CANDIDATE_BUTTONS = 8;

    public enum Screen { BOOT, CLOSED, IDLE, IDENTITY, FINGERPRINT, BALLOT, CONFIRM, SUCCESS, OFFICER }

    /** Everything the view can ask the controller to do. */
    public interface Listener {
        void onCardPresented(String cardIdentifier);
        void onIdentityConfirmed();
        void onFingerPlaced();
        void onCandidateSelected(int slotNumber);
        void onConfirmVote();
        void onCancel();
        void onOfficerPanelRequested();
        void onOpenElection();
        void onCloseElection();
        void onRefreshTurnout();
        void onSyncNow();
        void onReturnToIdle();
        void onRetryConnection();
    }

    private final Listener listener;
    private final JPanel   screens     = new JPanel(new CardLayout());
    private final CardLayout cardLayout;

    private Screen currentScreen = Screen.BOOT;

    // Status bar
    private final JLabel terminalLabel   = Theme.label("Terminal —", Theme.CAPTION, Theme.WHITE);
    private final JLabel electionLabel   = Theme.label("Election —", Theme.CAPTION, Theme.WHITE);
    private final JLabel hardwareLabel   = Theme.label("Hardware —", Theme.CAPTION, Theme.WHITE);
    private final JLabel queueLabel      = Theme.label("Queue —", Theme.CAPTION, Theme.WHITE);
    private final JComponent hardwareDot = Theme.statusDot(Theme.GREEN);
    private final JComponent queueDot    = Theme.statusDot(Theme.GREEN);

    // Boot
    private final JLabel  bootMessage  = Theme.centred("Starting up…", Theme.SUBHEAD, Theme.INK);
    private final JButton bootRetry    = Theme.pillButton("Retry connection", Theme.CHAKRA_NAVY, Theme.WHITE);

    // Closed
    private final JLabel closedMessage = Theme.centred("", Theme.BODY, Theme.INK_MUTED);

    // Idle
    private final JTextField cardField  = new JTextField(18);
    private final JLabel     idleError  = Theme.centred(" ", Theme.BODY_BOLD, Theme.ALERT);

    // Identity
    private final JLabel identityName        = Theme.label("—", Theme.HEADING, Theme.INK);
    private final JLabel identityVoterId     = Theme.label("—", Theme.MONO, Theme.INK_MUTED);
    private final JLabel identityConstituency = Theme.label("—", Theme.BODY, Theme.INK_MUTED);
    private final JLabel identityPhoto       = new JLabel();
    private final JLabel identityWarning     = Theme.label(" ", Theme.BODY_BOLD, Theme.ALERT);

    // Fingerprint
    private final Theme.PulsingRings fingerprintPad = new Theme.PulsingRings(180);
    private final JLabel fingerprintStatus = Theme.centred("Place your finger on the scanner",
            Theme.SUBHEAD, Theme.INK);
    private final JLabel fingerprintHint   = Theme.centred(" ", Theme.BODY, Theme.INK_MUTED);

    // Ballot
    private final JPanel ballotList     = Theme.column();
    private final JLabel ballotVoter    = Theme.label("—", Theme.BODY_BOLD, Theme.INK);
    private final JLabel ballotConstituency = Theme.label("—", Theme.CAPTION, Theme.INK_MUTED);
    private final JLabel ballotCountdown = Theme.label("", Theme.SUBHEAD, Theme.SAFFRON_DARK);
    private List<CandidateOption> ballotCandidates = List.of();

    // Confirm
    private final JLabel confirmSymbol    = new JLabel();
    private final JLabel confirmCandidate = Theme.centred("—", Theme.DISPLAY, Theme.CHAKRA_NAVY);
    private final JLabel confirmParty     = Theme.centred("—", Theme.SUBHEAD, Theme.INK_MUTED);
    private final JLabel confirmCountdown = Theme.centred("", Theme.BODY_BOLD, Theme.SAFFRON_DARK);

    // Success
    private final JLabel successHeadline = Theme.centred("Your vote has been recorded",
            Theme.HEADING, Theme.GREEN_DARK);
    private final JLabel successReceipt  = Theme.centred("—", Theme.MONO, Theme.INK_MUTED);
    private final JLabel successLedger   = Theme.centred("—", Theme.BODY, Theme.INK_MUTED);
    private final JLabel successNotice   = Theme.centred(" ", Theme.BODY_BOLD, Theme.SAFFRON_DARK);

    // Officer panel
    private final JTextArea officerOutput = new JTextArea(10, 46);
    private final JLabel    officerElection = Theme.label("—", Theme.BODY_BOLD, Theme.WHITE);

    public KioskFrame(Listener listener) {
        super("Electronic Voting Machine");
        this.listener   = listener;
        this.cardLayout = (CardLayout) screens.getLayout();

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 640));
        setLocationRelativeTo(null);

        getContentPane().setBackground(Theme.CANVAS);
        setLayout(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);
        add(buildScreens(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        installKeyRouting();
    }

    // ── Chrome ──────────────────────────────────────────────────────────────

    private JComponent buildHeader() {
        JComponent background = Theme.navyGradient();
        background.setLayout(new BorderLayout());

        JPanel row = Theme.transparent(new BorderLayout(16, 0));
        row.setBorder(Theme.padding(16, 26, 16, 26));

        JPanel emblemAndTitles = Theme.transparent(new BorderLayout(16, 0));
        emblemAndTitles.add(Theme.chakraEmblem(40, Theme.WHITE), BorderLayout.WEST);

        JPanel titles = Theme.column();
        titles.add(Theme.label("ELECTRONIC VOTING MACHINE", Theme.SUBHEAD, Theme.WHITE));
        titles.add(Theme.gap(2));
        titles.add(Theme.label("Election Commission — Secure Polling Terminal",
                Theme.CAPTION, new Color(0xC7, 0xD0, 0xE8)));
        emblemAndTitles.add(titles, BorderLayout.CENTER);

        row.add(emblemAndTitles, BorderLayout.CENTER);
        background.add(row, BorderLayout.CENTER);

        JPanel chrome = new JPanel(new BorderLayout());
        chrome.add(background, BorderLayout.CENTER);
        chrome.add(Theme.tricolourAccent(5), BorderLayout.SOUTH);
        return chrome;
    }

    private JComponent buildScreens() {
        screens.setBackground(Theme.CANVAS);
        screens.add(buildBootScreen(),        Screen.BOOT.name());
        screens.add(buildClosedScreen(),      Screen.CLOSED.name());
        screens.add(buildIdleScreen(),        Screen.IDLE.name());
        screens.add(buildIdentityScreen(),    Screen.IDENTITY.name());
        screens.add(buildFingerprintScreen(), Screen.FINGERPRINT.name());
        screens.add(buildBallotScreen(),      Screen.BALLOT.name());
        screens.add(buildConfirmScreen(),     Screen.CONFIRM.name());
        screens.add(buildSuccessScreen(),     Screen.SUCCESS.name());
        screens.add(buildOfficerScreen(),     Screen.OFFICER.name());
        return screens;
    }

    private JComponent buildStatusBar() {
        JComponent background = Theme.navyGradient();
        background.setLayout(new GridLayout(1, 4, 22, 0));
        background.setBorder(Theme.padding(10, 26, 10, 26));

        background.add(terminalLabel);
        background.add(electionLabel);
        background.add(withDot(hardwareDot, hardwareLabel));
        background.add(withDot(queueDot, queueLabel));

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(background, BorderLayout.CENTER);
        return bar;
    }

    private JComponent withDot(JComponent dot, JLabel label) {
        JPanel row = Theme.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.add(dot);
        row.add(label);
        return row;
    }

    // ── Screens ─────────────────────────────────────────────────────────────

    private JComponent buildBootScreen() {
        JPanel content = Theme.column();
        content.add(Box.createVerticalGlue());
        JComponent emblem = Theme.chakraEmblem(64, Theme.CHAKRA_NAVY);
        emblem.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(emblem);
        content.add(Theme.gap(18));
        content.add(Theme.centred("Connecting to the election server", Theme.HEADING, Theme.CHAKRA_NAVY));
        content.add(Theme.gap(16));
        bootMessage.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(bootMessage);
        content.add(Theme.gap(28));

        bootRetry.setAlignmentX(Component.CENTER_ALIGNMENT);
        bootRetry.addActionListener(event -> listener.onRetryConnection());
        content.add(bootRetry);
        content.add(Box.createVerticalGlue());
        return centreOnCanvas(content);
    }

    private JComponent buildClosedScreen() {
        JPanel content = Theme.column();
        content.add(Box.createVerticalGlue());
        content.add(Theme.centred("Polling is closed", Theme.DISPLAY, Theme.SAFFRON_DARK));
        content.add(Theme.gap(18));
        closedMessage.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(closedMessage);
        content.add(Theme.gap(24));
        content.add(Theme.centred("Ctrl + Shift + O  —  election officer panel",
                Theme.CAPTION, Theme.INK_MUTED));
        content.add(Box.createVerticalGlue());
        return centreOnCanvas(content);
    }

    private JComponent buildIdleScreen() {
        JPanel card = Theme.cardWithWatermark(340);
        card.setLayout(new BorderLayout());

        JPanel body = Theme.column();
        body.add(Theme.eyebrow("Tap your voter card", Theme.SAFFRON_DARK));
        body.add(Theme.gap(10));
        body.add(Theme.centred("Place your voter card on the reader", Theme.HEADING, Theme.CHAKRA_NAVY));
        body.add(Theme.gap(10));
        body.add(Theme.centred("वोटर कार्ड रीडर पर रखें  •  வாக்காளர் அட்டையை வைக்கவும்",
                Theme.BODY, Theme.INK_MUTED));
        body.add(Theme.gap(30));

        JPanel simulated = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 12, 0));
        simulated.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(Theme.SAFFRON, 2, 6, 4, true),
                Theme.padding(16, 20, 16, 20)));

        JLabel simulatedNote = Theme.label("Simulated reader — enter card or voter ID:",
                Theme.CAPTION, Theme.INK_MUTED);
        cardField.setFont(Theme.MONO);
        cardField.setColumns(16);
        cardField.getAccessibleContext().setAccessibleName("Voter card or voter ID");
        cardField.addActionListener(event -> submitCard());

        JButton scan = Theme.pillButton("Read card", Theme.SAFFRON, Theme.INK);
        scan.addActionListener(event -> submitCard());

        simulated.add(simulatedNote);
        simulated.add(cardField);
        simulated.add(scan);
        simulated.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(simulated);

        body.add(Theme.gap(18));
        idleError.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(idleError);
        card.add(body, BorderLayout.CENTER);

        JPanel content = Theme.column();
        content.add(Box.createVerticalGlue());
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(card);
        content.add(Theme.gap(20));
        content.add(Theme.centred("Ctrl + Shift + O  —  election officer panel",
                Theme.CAPTION, Theme.INK_MUTED));
        content.add(Box.createVerticalGlue());
        return centreOnCanvas(content);
    }

    private JComponent buildIdentityScreen() {
        JPanel card = Theme.card();
        card.setLayout(new BorderLayout(28, 0));

        JPanel photoFrame = Theme.flatSurface(Theme.WHITE, 14);
        photoFrame.setLayout(new BorderLayout());
        photoFrame.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.HAIRLINE, 1),
                Theme.padding(3, 3, 3, 3)));
        identityPhoto.setPreferredSize(new Dimension(154, 184));
        identityPhoto.setHorizontalAlignment(SwingConstants.CENTER);
        identityPhoto.setVerticalAlignment(SwingConstants.CENTER);
        identityPhoto.setFont(Theme.CAPTION);
        identityPhoto.setForeground(Theme.INK_MUTED);
        photoFrame.add(identityPhoto, BorderLayout.CENTER);
        card.add(photoFrame, BorderLayout.WEST);

        JPanel details = Theme.column();
        details.add(Theme.label("CONFIRM YOUR IDENTITY", Theme.EYEBROW, Theme.SAFFRON_DARK));
        details.add(Theme.gap(14));
        details.add(identityName);
        details.add(Theme.gap(6));
        details.add(identityVoterId);
        details.add(Theme.gap(6));
        details.add(identityConstituency);
        details.add(Theme.gap(14));
        details.add(identityWarning);
        card.add(details, BorderLayout.CENTER);

        JPanel actions = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 20, 0));
        actions.add(actionPrompt("ENTER", "This is me", Theme.GREEN, Theme.WHITE,
                event -> listener.onIdentityConfirmed()));
        actions.add(actionPromptOutline("ESC", "Not me — cancel", Theme.ALERT,
                event -> listener.onCancel()));

        return stack(card, actions);
    }

    private JComponent buildFingerprintScreen() {
        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        fingerprintStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(fingerprintStatus);
        card.add(Theme.gap(8));
        card.add(Theme.centred("फिंगरप्रिंट स्कैन करें  •  கைரேகையை வைக்கவும்",
                Theme.BODY, Theme.INK_MUTED));
        card.add(Theme.gap(26));

        fingerprintPad.setAlignmentX(Component.CENTER_ALIGNMENT);
        fingerprintPad.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fingerprintPad.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                listener.onFingerPlaced();
            }
        });
        fingerprintPad.getAccessibleContext().setAccessibleName("Fingerprint scanner pad");
        card.add(fingerprintPad);

        card.add(Theme.gap(18));
        fingerprintHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(fingerprintHint);

        JPanel actions = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 20, 0));
        actions.add(actionPrompt("ENTER", "Scan finger", Theme.GREEN, Theme.WHITE,
                event -> listener.onFingerPlaced()));
        actions.add(actionPromptOutline("ESC", "Cancel", Theme.ALERT,
                event -> listener.onCancel()));

        return stack(card, actions);
    }

    private JComponent buildBallotScreen() {
        JPanel header = Theme.transparent(new BorderLayout());
        header.setBorder(Theme.padding(0, 0, 12, 0));

        JPanel voterInfo = Theme.column();
        voterInfo.add(ballotVoter);
        voterInfo.add(Theme.gap(2));
        voterInfo.add(ballotConstituency);
        header.add(voterInfo, BorderLayout.WEST);
        header.add(ballotCountdown, BorderLayout.EAST);

        JPanel card = Theme.card();
        card.setLayout(new BorderLayout());
        card.add(header, BorderLayout.NORTH);

        ballotList.setBorder(Theme.padding(4, 0, 4, 0));
        ballotList.setOpaque(true);
        ballotList.setBackground(Theme.WHITE);
        JScrollPane scroller = new JScrollPane(ballotList);
        scroller.setBorder(BorderFactory.createLineBorder(Theme.HAIRLINE));
        scroller.getViewport().setBackground(Theme.WHITE);
        scroller.getVerticalScrollBar().setUnitIncrement(24);
        card.add(scroller, BorderLayout.CENTER);

        JPanel actions = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 20, 0));
        actions.add(Theme.label("Press the numbered button beside your choice",
                Theme.BODY_BOLD, Theme.INK));
        actions.add(actionPromptOutline("ESC", "Cancel", Theme.ALERT, event -> listener.onCancel()));

        return stack(card, actions);
    }

    private JComponent buildConfirmScreen() {
        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.add(Theme.eyebrow("You are voting for", Theme.INK_MUTED));
        card.add(Theme.gap(14));
        confirmSymbol.setAlignmentX(Component.CENTER_ALIGNMENT);
        confirmSymbol.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(confirmSymbol);
        card.add(Theme.gap(10));
        card.add(confirmCandidate);
        card.add(Theme.gap(8));
        card.add(confirmParty);
        card.add(Theme.gap(22));
        card.add(Theme.centred("This cannot be changed once confirmed.", Theme.BODY, Theme.ALERT));
        card.add(Theme.gap(10));
        card.add(confirmCountdown);

        JPanel actions = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 20, 0));
        actions.add(actionPrompt("ENTER", "Confirm vote", Theme.GREEN, Theme.WHITE,
                event -> listener.onConfirmVote()));
        actions.add(actionPromptOutline("ESC", "Go back", Theme.ALERT, event -> listener.onCancel()));

        return stack(card, actions);
    }

    private JComponent buildSuccessScreen() {
        JPanel card = Theme.cardWithWatermark(300);
        card.setLayout(new BorderLayout());

        JPanel body = Theme.column();
        JComponent glyphRow = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 0, 0));
        glyphRow.add(Theme.inkMarkGlyph(64, 92));
        body.add(glyphRow);
        body.add(Theme.gap(14));
        successHeadline.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(successHeadline);
        body.add(Theme.gap(8));
        body.add(Theme.centred("धन्यवाद  •  நன்றி", Theme.BODY, Theme.INK_MUTED));
        body.add(Theme.gap(22));
        body.add(Theme.centred("Receipt", Theme.CAPTION, Theme.INK_MUTED));
        successReceipt.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(successReceipt);
        body.add(Theme.gap(12));
        successLedger.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(successLedger);
        body.add(Theme.gap(12));
        successNotice.setAlignmentX(Component.CENTER_ALIGNMENT);
        body.add(successNotice);
        card.add(body, BorderLayout.CENTER);

        JPanel actions = Theme.transparent(new FlowLayout(FlowLayout.CENTER, 20, 0));
        actions.add(actionPrompt("ENTER", "Next voter", Theme.GREEN, Theme.WHITE,
                event -> listener.onReturnToIdle()));

        JPanel content = Theme.column();
        content.add(Box.createVerticalGlue());
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        actions.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(card);
        content.add(Theme.gap(20));
        content.add(actions);
        content.add(Box.createVerticalGlue());
        return centreOnCanvas(content);
    }

    private JComponent buildOfficerScreen() {
        JPanel card = Theme.flatSurface(Theme.CHAKRA_NAVY_DEEP, 22);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.CHAKRA_NAVY, 1),
                Theme.padding(26, 30, 26, 30)));
        card.setLayout(new BorderLayout(0, 16));

        JPanel top = Theme.column();
        top.add(Theme.label("ELECTION OFFICER PANEL", Theme.EYEBROW, Theme.SAFFRON));
        top.add(Theme.gap(8));
        top.add(officerElection);
        card.add(top, BorderLayout.NORTH);

        officerOutput.setEditable(false);
        officerOutput.setFont(Theme.MONO);
        officerOutput.setLineWrap(true);
        officerOutput.setWrapStyleWord(true);
        officerOutput.setBackground(Theme.CHAKRA_NAVY_DEEP.darker());
        officerOutput.setForeground(new Color(0x8F, 0xE9, 0x9B));
        officerOutput.setCaretColor(Theme.WHITE);
        officerOutput.getAccessibleContext().setAccessibleName("Officer panel output");
        JScrollPane scroller = new JScrollPane(officerOutput);
        scroller.setBorder(BorderFactory.createLineBorder(Theme.CHAKRA_NAVY));
        card.add(scroller, BorderLayout.CENTER);

        JPanel buttons = Theme.transparent(new FlowLayout(FlowLayout.LEFT, 12, 0));

        JButton openButton = Theme.pillButton("Open polling", Theme.GREEN, Theme.WHITE);
        openButton.addActionListener(event -> listener.onOpenElection());
        buttons.add(openButton);

        JButton closeButton = Theme.pillButton("Close polling", Theme.SAFFRON_DARK, Theme.WHITE);
        closeButton.addActionListener(event -> listener.onCloseElection());
        buttons.add(closeButton);

        JButton refreshButton = Theme.pillButton("Refresh turnout", Theme.CHAKRA_NAVY, Theme.WHITE);
        refreshButton.addActionListener(event -> listener.onRefreshTurnout());
        buttons.add(refreshButton);

        JButton syncButton = Theme.pillButton("Sync now", Theme.CHAKRA_NAVY, Theme.WHITE);
        syncButton.addActionListener(event -> listener.onSyncNow());
        buttons.add(syncButton);

        JButton backButton = Theme.outlineButton("Back", Theme.WHITE);
        backButton.addActionListener(event -> listener.onReturnToIdle());
        buttons.add(backButton);

        card.add(buttons, BorderLayout.SOUTH);

        return centreOnCanvas(card);
    }

    // ── Screen state ────────────────────────────────────────────────────────

    public void show(Screen screen) {
        Screen previous = this.currentScreen;
        this.currentScreen = screen;
        cardLayout.show(screens, screen.name());

        if (previous == Screen.FINGERPRINT && screen != Screen.FINGERPRINT) {
            fingerprintPad.stop();
        }
        if (screen == Screen.FINGERPRINT) {
            fingerprintPad.start();
        }

        if (screen == Screen.IDLE) {
            cardField.setText("");
            SwingUtilities.invokeLater(cardField::requestFocusInWindow);
        } else {
            SwingUtilities.invokeLater(this::requestFocusInWindow);
        }
    }

    public Screen currentScreen() {
        return currentScreen;
    }

    public void setBootMessage(String message, boolean offerRetry) {
        bootMessage.setText(message);
        bootRetry.setVisible(offerRetry);
    }

    public void setClosedMessage(String message) {
        closedMessage.setText(message);
    }

    public void setIdleError(String message) {
        idleError.setText(message == null || message.isEmpty() ? " " : message);
    }

    public void setTerminalStatus(String machineId, String label) {
        terminalLabel.setText("Terminal: " + machineId + (label == null ? "" : " — " + label));
    }

    public void setElectionStatus(String text) {
        electionLabel.setText("Election: " + text);
    }

    public void setHardwareStatus(String text) {
        hardwareLabel.setText("Hardware: " + text);
        String lower = text == null ? "" : text.toLowerCase();
        boolean healthy = !lower.contains("fail") && !lower.contains("error") && !lower.contains("unavailable");
        hardwareDot.setBackground(healthy ? Theme.GREEN : Theme.ALERT);
        hardwareDot.repaint();
    }

    public void setQueueStatus(String text, boolean healthy) {
        queueLabel.setText("Queue: " + text);
        queueDot.setBackground(healthy ? Theme.GREEN : Theme.SAFFRON);
        queueDot.repaint();
    }

    public void showIdentity(String name, String voterId, String constituency,
                            String photoBase64, String warning) {
        identityName.setText(name);
        identityVoterId.setText("Voter ID  " + voterId);
        identityConstituency.setText(constituency);
        identityWarning.setText(warning == null || warning.isEmpty() ? " " : warning);
        identityPhoto.setIcon(decodePhoto(photoBase64));
        identityPhoto.setText(identityPhoto.getIcon() == null ? "No photo on file" : null);
    }

    public void setFingerprintState(String status, String hint) {
        fingerprintStatus.setText(status);
        fingerprintHint.setText(hint == null || hint.isEmpty() ? " " : hint);
    }

    /** Renders the ballot, one row per candidate, each labelled with its physical button
     *  and its party symbol where one has been uploaded. */
    public void showBallot(String voterName, String constituencyName,
                          List<CandidateOption> candidates, Map<Integer, ImageIcon> symbolImages) {
        this.ballotCandidates = candidates == null ? List.of() : candidates;
        ballotVoter.setText("Voter: " + voterName);
        ballotConstituency.setText(constituencyName == null || constituencyName.isEmpty()
                ? " " : "Ballot for: " + constituencyName);
        ballotList.removeAll();

        for (CandidateOption candidate : ballotCandidates) {
            ballotList.add(buildCandidateRow(candidate, symbolImages));
            ballotList.add(Theme.gap(6));
        }
        if (ballotCandidates.size() > CANDIDATE_BUTTONS) {
            ballotList.add(Theme.label(
                    "Only the first " + CANDIDATE_BUTTONS + " candidates can be selected on this terminal.",
                    Theme.CAPTION, Theme.ALERT));
        }
        ballotList.revalidate();
        ballotList.repaint();
    }

    private JComponent buildCandidateRow(CandidateOption candidate, Map<Integer, ImageIcon> symbolImages) {
        JPanel row = new JPanel(new BorderLayout(16, 0)) {
            private boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(hover ? Theme.CANVAS : Theme.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        row.setOpaque(true);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.HAIRLINE),
                Theme.padding(12, 14, 12, 14)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));

        boolean selectable = candidate.slotNumber() <= CANDIDATE_BUTTONS;
        JPanel leading = Theme.transparent(new FlowLayout(FlowLayout.LEFT, 12, 0));
        leading.add(Theme.keyCap(selectable ? String.valueOf(candidate.slotNumber()) : "—",
                selectable ? Theme.SAFFRON : Theme.HAIRLINE,
                selectable ? Theme.INK : Theme.INK_MUTED));
        leading.add(symbolBadge(symbolImages.get(candidate.id())));
        row.add(leading, BorderLayout.WEST);

        JPanel names = Theme.column();
        names.add(Theme.label(candidate.name(), Theme.SUBHEAD, Theme.INK));
        String secondary = candidate.nameTa() == null ? "" : candidate.nameTa();
        String party = candidate.party() == null ? "Independent" : candidate.party();
        names.add(Theme.label(secondary.isEmpty() ? party : secondary + "  •  " + party,
                Theme.BODY, Theme.INK_MUTED));
        row.add(names, BorderLayout.CENTER);

        if (selectable) {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    listener.onCandidateSelected(candidate.slotNumber());
                }
            });
        }
        return row;
    }

    public void showConfirmation(CandidateOption candidate, Map<Integer, ImageIcon> symbolImages) {
        confirmCandidate.setText(candidate.name());
        confirmParty.setText(candidate.party() == null ? "Independent" : candidate.party());
        ImageIcon symbol = symbolImages.get(candidate.id());
        confirmSymbol.setIcon(symbol);
        confirmSymbol.setPreferredSize(new Dimension(SYMBOL_BADGE_SIZE, SYMBOL_BADGE_SIZE));
        confirmSymbol.setVisible(symbol != null);
    }

    private static final int SYMBOL_BADGE_SIZE = 44;

    /** A small bordered square for a candidate's party symbol, or an empty placeholder of
     *  the same size so rows line up whether or not a symbol was uploaded. */
    private JComponent symbolBadge(ImageIcon symbol) {
        JLabel badge = new JLabel(symbol);
        badge.setHorizontalAlignment(SwingConstants.CENTER);
        badge.setPreferredSize(new Dimension(SYMBOL_BADGE_SIZE, SYMBOL_BADGE_SIZE));
        badge.setOpaque(symbol == null);
        badge.setBackground(Theme.CANVAS);
        if (symbol == null) {
            badge.setBorder(BorderFactory.createLineBorder(Theme.HAIRLINE, 1));
        }
        return badge;
    }

    public void setCountdown(int secondsRemaining) {
        String text = secondsRemaining <= 0 ? "" : "Time remaining: " + secondsRemaining + "s";
        ballotCountdown.setText(text);
        confirmCountdown.setText(text);
    }

    public void showSuccess(String headline, String receipt, String ledger, String notice) {
        successHeadline.setText(headline);
        successReceipt.setText(receipt);
        successLedger.setText(ledger);
        successNotice.setText(notice == null || notice.isEmpty() ? " " : notice);
    }

    public void setOfficerElection(String text) {
        officerElection.setText(text);
    }

    public void appendOfficerOutput(String text) {
        officerOutput.append(text + System.lineSeparator());
        officerOutput.setCaretPosition(officerOutput.getDocument().getLength());
    }

    public void clearOfficerOutput() {
        officerOutput.setText("");
    }

    public List<CandidateOption> ballotCandidates() {
        return ballotCandidates;
    }

    public void showMessage(String title, String message, boolean error) {
        JOptionPane.showMessageDialog(this, message, title,
                error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Input routing ───────────────────────────────────────────────────────

    /**
     * Routes the ten physical buttons.
     *
     * <p>A single dispatcher rather than per-component key listeners, because the enclosure's
     * buttons are global inputs, not focus-dependent ones. Text entry on the simulated reader
     * is respected: while a field has focus, characters go to the field.
     */
    private void installKeyRouting() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(this::routeKey);
    }

    private boolean routeKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED || !isActive()) {
            return false;
        }
        int  code    = event.getKeyCode();
        boolean typing = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .getFocusOwner() instanceof JTextComponent;

        if (event.isControlDown() && event.isShiftDown() && code == KeyEvent.VK_O) {
            listener.onOfficerPanelRequested();
            return true;
        }

        switch (currentScreen) {
            case IDLE -> {
                if (code == KeyEvent.VK_ENTER) {
                    submitCard();
                    return true;
                }
            }
            case IDENTITY -> {
                if (code == KeyEvent.VK_ENTER) { listener.onIdentityConfirmed(); return true; }
                if (code == KeyEvent.VK_ESCAPE) { listener.onCancel(); return true; }
            }
            case FINGERPRINT -> {
                if (code == KeyEvent.VK_ENTER) { listener.onFingerPlaced(); return true; }
                if (code == KeyEvent.VK_ESCAPE) { listener.onCancel(); return true; }
            }
            case BALLOT -> {
                if (!typing && code >= KeyEvent.VK_1 && code <= KeyEvent.VK_8) {
                    listener.onCandidateSelected(code - KeyEvent.VK_0);
                    return true;
                }
                if (code == KeyEvent.VK_ESCAPE) { listener.onCancel(); return true; }
            }
            case CONFIRM -> {
                if (code == KeyEvent.VK_ENTER) { listener.onConfirmVote(); return true; }
                if (code == KeyEvent.VK_ESCAPE) { listener.onCancel(); return true; }
            }
            case SUCCESS -> {
                if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_ESCAPE) {
                    listener.onReturnToIdle();
                    return true;
                }
            }
            case OFFICER -> {
                if (code == KeyEvent.VK_ESCAPE) { listener.onReturnToIdle(); return true; }
            }
            case BOOT, CLOSED -> {
                if (code == KeyEvent.VK_ENTER) { listener.onRetryConnection(); return true; }
            }
        }
        return false;
    }

    private void submitCard() {
        String identifier = cardField.getText().trim();
        if (identifier.isEmpty()) {
            setIdleError("Enter a card or voter ID to simulate a tap.");
            return;
        }
        setIdleError(null);
        listener.onCardPresented(identifier);
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private JComponent centreOnCanvas(JComponent content) {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setBackground(Theme.CANVAS);
        wrapper.setBorder(Theme.padding(24, 40, 24, 40));
        wrapper.add(content);
        return wrapper;
    }

    private JComponent stack(JComponent card, JComponent actions) {
        JPanel wrapper = new JPanel(new BorderLayout(0, 20));
        wrapper.setBackground(Theme.CANVAS);
        wrapper.setBorder(Theme.padding(24, 40, 24, 40));
        wrapper.add(card, BorderLayout.CENTER);
        wrapper.add(actions, BorderLayout.SOUTH);
        return wrapper;
    }

    /** A key-cap paired with its action, so the on-screen label always names the button. */
    private JComponent actionPrompt(String key, String text, Color accent, Color foreground,
                                    java.awt.event.ActionListener action) {
        JPanel prompt = Theme.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        prompt.add(Theme.keyCap(key, accent, Theme.WHITE));

        JButton button = Theme.pillButton(text, accent, foreground);
        button.addActionListener(action);
        prompt.add(button);
        return prompt;
    }

    private JComponent actionPromptOutline(String key, String text, Color accent,
                                           java.awt.event.ActionListener action) {
        JPanel prompt = Theme.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        prompt.add(Theme.keyCap(key, accent, Theme.WHITE));

        JButton button = Theme.outlineButton(text, accent);
        button.addActionListener(action);
        prompt.add(button);
        return prompt;
    }

    private static ImageIcon decodePhoto(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            Image image = new ImageIcon(bytes).getImage();
            if (image == null || image.getWidth(null) <= 0) {
                return null;
            }
            return new ImageIcon(image.getScaledInstance(150, 180, Image.SCALE_SMOOTH));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Kiosk mode: full screen and undecorated, for the assembled enclosure. */
    public void enterKioskMode() {
        setUndecorated(true);
        setExtendedState(Frame.MAXIMIZED_BOTH);
    }

    @SuppressWarnings("unused")
    private static List<CandidateOption> emptyBallot() {
        return new ArrayList<>();
    }
}

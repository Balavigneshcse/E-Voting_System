package machine;

import machine.api.ApiClient;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.InputStream;
import java.util.*;
import java.util.List;
import java.util.Timer;

/**
 * VotingMachineApp — Swing GUI that simulates the physical voting machine.
 *
 * SCREENS:
 *   BOOT       → Connects to server, registers machine
 *   IDLE       → "Please scan your voter card" (type voter ID)
 *   FINGERPRINT → "Please verify fingerprint" (click Verify button)
 *   VOTING     → Shows candidates with vote buttons (simulates physical LED buttons)
 *   CONFIRM    → "Are you sure?" confirmation
 *   SUCCESS    → "Vote recorded" with receipt
 *   ADMIN      → Admin login + election control
 *   ERROR      → Error with retry
 */
public class VotingMachineApp extends JFrame {

    // ── Config ────────────────────────────────────────────
    private static Properties config;
    private static ApiClient  api;

    // ── State ─────────────────────────────────────────────
    private enum Screen { BOOT, IDLE, FINGERPRINT, VOTING, CONFIRM, SUCCESS, ADMIN_LOGIN, ADMIN_PANEL }
    private Screen currentScreen = Screen.BOOT;

    private String currentVoterId    = null;
    private String currentVoterName  = null;
    private String currentSessionToken = null;
    private List<Map<String,Object>> currentCandidates = new ArrayList<>();
    private int    selectedCandidateId   = -1;
    private String selectedCandidateName = "";
    private String adminToken = null;

    // ── Timeout ───────────────────────────────────────────
    private Timer  sessionTimer = null;
    private int    timeoutSeconds;

    // ── UI Colors (matches 3D model blue/white theme) ─────
    private static final Color CLR_BLUE    = new Color(0x1A4F8A);
    private static final Color CLR_LBLUE   = new Color(0x2979C7);
    private static final Color CLR_WHITE   = Color.WHITE;
    private static final Color CLR_GREEN   = new Color(0x27AE60);
    private static final Color CLR_RED     = new Color(0xC0392B);
    private static final Color CLR_AMBER   = new Color(0xF39C12);
    private static final Color CLR_BG      = new Color(0xF0F4F8);
    private static final Color CLR_DARK    = new Color(0x1A1A2E);

    // ── UI Panels ─────────────────────────────────────────
    private JPanel       mainPanel;
    private CardLayout   cardLayout;
    private JLabel       statusBar;

    public VotingMachineApp() {
        setTitle("E-Voting Machine — " + config.getProperty("MACHINE_ID", "PI-WARD-01"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setMinimumSize(new Dimension(700, 500));
        setLocationRelativeTo(null);
        getContentPane().setBackground(CLR_BG);

        cardLayout = new CardLayout();
        mainPanel  = new JPanel(cardLayout);
        mainPanel.setBackground(CLR_BG);

        // Build all screens
        mainPanel.add(buildBootScreen(),        "BOOT");
        mainPanel.add(buildIdleScreen(),        "IDLE");
        mainPanel.add(buildFingerprintScreen(), "FINGERPRINT");
        mainPanel.add(buildVotingScreen(),      "VOTING");
        mainPanel.add(buildConfirmScreen(),     "CONFIRM");
        mainPanel.add(buildSuccessScreen(),     "SUCCESS");
        mainPanel.add(buildAdminLoginScreen(),  "ADMIN_LOGIN");
        mainPanel.add(buildAdminPanelScreen(),  "ADMIN_PANEL");

        // Status bar at bottom
        statusBar = new JLabel("Starting...", SwingConstants.CENTER);
        statusBar.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusBar.setForeground(new Color(0x555555));
        statusBar.setBorder(new EmptyBorder(4, 10, 4, 10));
        statusBar.setBackground(new Color(0xDDE3EA));
        statusBar.setOpaque(true);

        add(mainPanel, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        setVisible(true);
        showScreen("BOOT");

        // Auto-boot after 1 second
        SwingUtilities.invokeLater(() -> {
            Timer t = new Timer();
            t.schedule(new TimerTask() {
                public void run() { SwingUtilities.invokeLater(() -> bootMachine()); }
            }, 1000);
        });
    }

    // ═══════════════════════════════════════════════════════
    //  SCREEN BUILDERS
    // ═══════════════════════════════════════════════════════

    private JPanel buildBootScreen() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CLR_DARK);

        JLabel logo = new JLabel("🗳  E-VOTING MACHINE", SwingConstants.CENTER);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 28));
        logo.setForeground(CLR_WHITE);
        logo.setBorder(new EmptyBorder(80, 20, 10, 20));

        JLabel sub = new JLabel("Connecting to server...", SwingConstants.CENTER);
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        sub.setForeground(new Color(0xAAAAAA));
        sub.setName("bootStatus");

        JLabel ward = new JLabel("Ward: " + config.getProperty("MACHINE_ID"), SwingConstants.CENTER);
        ward.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        ward.setForeground(new Color(0x7FAADD));

        JPanel center = new JPanel(new GridLayout(3, 1, 0, 10));
        center.setBackground(CLR_DARK);
        center.add(logo);
        center.add(sub);
        center.add(ward);
        p.add(center, BorderLayout.CENTER);

        JLabel footer = new JLabel("Biometric Authentication  |  Physical Button Interface  |  Blockchain Secured",
            SwingConstants.CENTER);
        footer.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        footer.setForeground(new Color(0x555588));
        footer.setBorder(new EmptyBorder(0, 0, 20, 0));
        p.add(footer, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildIdleScreen() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(CLR_BG);

        // Header
        JPanel header = makeHeader("SCAN VOTER CARD", "Show your voter ID card to the RFID reader");
        p.add(header, BorderLayout.NORTH);

        // Center — voter ID input (simulates RFID scanner)
        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(CLR_BG);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(10, 20, 10, 20);
        gc.fill   = GridBagConstraints.HORIZONTAL;
        gc.gridx  = 0; gc.gridy = 0; gc.gridwidth = 2;

        JLabel hint = new JLabel("For demo: type Voter ID below (simulates RFID scan)");
        hint.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        hint.setForeground(new Color(0x777777));
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(hint, gc);

        gc.gridy = 1;
        JTextField voterIdField = new JTextField(20);
        voterIdField.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        voterIdField.setHorizontalAlignment(JTextField.CENTER);
        voterIdField.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(CLR_BLUE, 2, true),
            new EmptyBorder(8, 12, 8, 12)));
        voterIdField.setName("voterIdField");
        center.add(voterIdField, gc);

        gc.gridy = 2; gc.gridwidth = 1;
        JButton scanBtn = makeButton("SCAN  ▶", CLR_BLUE, CLR_WHITE);
        scanBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        scanBtn.addActionListener(e -> {
            String id = voterIdField.getText().trim();
            if (id.isEmpty()) { showError("Please enter your Voter ID."); return; }
            voterIdField.setText("");
            handleCardScan(id);
        });
        voterIdField.addActionListener(e -> scanBtn.doClick());
        center.add(scanBtn, gc);

        gc.gridx = 1;
        JButton adminBtn = makeButton("Admin", new Color(0x666666), CLR_WHITE);
        adminBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        adminBtn.addActionListener(e -> showScreen("ADMIN_LOGIN"));
        center.add(adminBtn, gc);

        p.add(center, BorderLayout.CENTER);

        // Election info at bottom
        JPanel infoPanel = new JPanel(new FlowLayout());
        infoPanel.setBackground(CLR_BG);
        JLabel infoLabel = new JLabel();
        infoLabel.setName("electionInfoLabel");
        infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        infoLabel.setForeground(CLR_BLUE);
        infoPanel.add(infoLabel);
        p.add(infoPanel, BorderLayout.SOUTH);

        return p;
    }

    private JPanel buildFingerprintScreen() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CLR_BG);
        p.add(makeHeader("FINGERPRINT VERIFICATION", "Place your finger on the scanner"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(CLR_BG);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(12, 20, 12, 20);
        gc.gridx = 0; gc.gridy = 0;

        JLabel voterLabel = new JLabel("Voter: Loading...");
        voterLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        voterLabel.setForeground(CLR_DARK);
        voterLabel.setName("fpVoterLabel");
        center.add(voterLabel, gc);

        gc.gridy = 1;
        // Fingerprint icon (large button to simulate scanner press)
        JButton fpBtn = new JButton("👆");
        fpBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 72));
        fpBtn.setPreferredSize(new Dimension(150, 150));
        fpBtn.setBackground(new Color(0xE8F4FD));
        fpBtn.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(CLR_BLUE, 3, true), new EmptyBorder(10, 10, 10, 10)));
        fpBtn.setFocusPainted(false);
        fpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fpBtn.setToolTipText("Click to simulate fingerprint scan");
        fpBtn.addActionListener(e -> handleFingerprintVerify());
        center.add(fpBtn, gc);

        gc.gridy = 2;
        JLabel hint = new JLabel("For demo: click the fingerprint button to verify");
        hint.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        hint.setForeground(new Color(0x777777));
        center.add(hint, gc);

        gc.gridy = 3;
        JButton cancelFpBtn = makeButton("Cancel", CLR_RED, CLR_WHITE);
        cancelFpBtn.addActionListener(e -> {
            currentVoterId = null;
            currentVoterName = null;
            showScreen("IDLE");
            setStatus("Fingerprint cancelled.");
        });
        center.add(cancelFpBtn, gc);

        p.add(center, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildVotingScreen() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(CLR_BG);
        p.add(makeHeader("CAST YOUR VOTE", "Press the button next to your chosen candidate"), BorderLayout.NORTH);

        // Candidate buttons panel — will be populated dynamically
        JPanel candidatesPanel = new JPanel();
        candidatesPanel.setName("candidatesPanel");
        candidatesPanel.setLayout(new BoxLayout(candidatesPanel, BoxLayout.Y_AXIS));
        candidatesPanel.setBackground(CLR_BG);
        candidatesPanel.setBorder(new EmptyBorder(10, 40, 10, 40));

        JScrollPane scroll = new JScrollPane(candidatesPanel);
        scroll.setBorder(null);
        scroll.setBackground(CLR_BG);
        scroll.getViewport().setBackground(CLR_BG);
        scroll.setName("candidatesScroll");
        p.add(scroll, BorderLayout.CENTER);

        // Bottom info + cancel
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(CLR_BG);
        bottom.setBorder(new EmptyBorder(8, 20, 10, 20));

        JLabel voterInfo = new JLabel("Voter: —");
        voterInfo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        voterInfo.setForeground(new Color(0x555555));
        voterInfo.setName("votingVoterInfo");
        bottom.add(voterInfo, BorderLayout.WEST);

        JLabel timerLabel = new JLabel("Time remaining: 2:00");
        timerLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        timerLabel.setForeground(CLR_AMBER);
        timerLabel.setName("timerLabel");
        bottom.add(timerLabel, BorderLayout.CENTER);

        JButton cancelVoteBtn = makeButton("Cancel", CLR_RED, CLR_WHITE);
        cancelVoteBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        cancelVoteBtn.addActionListener(e -> {
            if (currentSessionToken != null) {
                new Thread(() -> {
                    try { api.cancelSession(currentSessionToken); } catch (Exception ignored) {}
                }).start();
            }
            stopSessionTimer();
            currentSessionToken = null;
            showScreen("IDLE");
            setStatus("Vote cancelled by voter.");
        });
        bottom.add(cancelVoteBtn, BorderLayout.EAST);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildConfirmScreen() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CLR_BG);
        p.add(makeHeader("CONFIRM YOUR VOTE", "Please confirm your selection"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(CLR_BG);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(10, 20, 10, 20);
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;

        JLabel confirmLabel = new JLabel("You selected:");
        confirmLabel.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        center.add(confirmLabel, gc);

        gc.gridy = 1;
        JLabel candidateLabel = new JLabel("...");
        candidateLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        candidateLabel.setForeground(CLR_BLUE);
        candidateLabel.setName("confirmCandidateLabel");
        center.add(candidateLabel, gc);

        gc.gridy = 2;
        JLabel warning = new JLabel("⚠  You CANNOT change your vote after confirming.");
        warning.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        warning.setForeground(CLR_AMBER);
        center.add(warning, gc);

        gc.gridy = 3; gc.gridwidth = 1; gc.fill = GridBagConstraints.NONE;
        JButton confirmBtn = makeButton("✔  CONFIRM VOTE", CLR_GREEN, CLR_WHITE);
        confirmBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        confirmBtn.setPreferredSize(new Dimension(200, 50));
        confirmBtn.addActionListener(e -> handleCastVote());
        center.add(confirmBtn, gc);

        gc.gridx = 1;
        JButton backBtn = makeButton("← GO BACK", CLR_AMBER, CLR_WHITE);
        backBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        backBtn.setPreferredSize(new Dimension(160, 50));
        backBtn.addActionListener(e -> showScreen("VOTING"));
        center.add(backBtn, gc);

        p.add(center, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildSuccessScreen() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0xF0FFF4));

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(new Color(0xF0FFF4));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(12, 20, 12, 20);
        gc.gridx = 0; gc.gridy = 0;

        JLabel tick = new JLabel("✅");
        tick.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 72));
        center.add(tick, gc);

        gc.gridy = 1;
        JLabel title = new JLabel("VOTE RECORDED SUCCESSFULLY");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(CLR_GREEN);
        center.add(title, gc);

        gc.gridy = 2;
        JLabel receiptLabel = new JLabel();
        receiptLabel.setFont(new Font("Consolas", Font.PLAIN, 14));
        receiptLabel.setForeground(new Color(0x555555));
        receiptLabel.setName("receiptLabel");
        center.add(receiptLabel, gc);

        gc.gridy = 3;
        JLabel thankYou = new JLabel("Thank you for voting! Your vote is secured on the blockchain.");
        thankYou.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        thankYou.setForeground(new Color(0x444444));
        center.add(thankYou, gc);

        gc.gridy = 4;
        JButton nextVoterBtn = makeButton("Next Voter →", CLR_BLUE, CLR_WHITE);
        nextVoterBtn.setFont(new Font("Segoe UI", Font.BOLD, 16));
        nextVoterBtn.setPreferredSize(new Dimension(200, 50));
        nextVoterBtn.addActionListener(e -> {
            currentVoterId    = null;
            currentVoterName  = null;
            currentSessionToken = null;
            currentCandidates = new ArrayList<>();
            showScreen("IDLE");
            updateElectionInfo();
        });
        center.add(nextVoterBtn, gc);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildAdminLoginScreen() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(CLR_BG);
        p.add(makeHeader("ADMIN LOGIN", "Election Officer Authentication"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(CLR_BG);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 20, 8, 20);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridwidth = 2; gc.gridx = 0; gc.gridy = 0;

        JTextField adminUser = new JTextField(15);
        adminUser.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        adminUser.setBorder(makeBorder("Username"));
        adminUser.setName("adminUser");
        center.add(adminUser, gc);

        gc.gridy = 1;
        JPasswordField adminPass = new JPasswordField(15);
        adminPass.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        adminPass.setBorder(makeBorder("Password"));
        center.add(adminPass, gc);

        gc.gridy = 2; gc.gridwidth = 1;
        JButton loginBtn = makeButton("Login", CLR_BLUE, CLR_WHITE);
        loginBtn.addActionListener(e -> {
            String u = adminUser.getText().trim();
            String pw = new String(adminPass.getPassword());
            handleAdminLogin(u, pw);
        });
        center.add(loginBtn, gc);

        gc.gridx = 1;
        JButton backBtn = makeButton("Back", new Color(0x888888), CLR_WHITE);
        backBtn.addActionListener(e -> showScreen("IDLE"));
        center.add(backBtn, gc);

        p.add(center, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildAdminPanelScreen() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(CLR_BG);
        p.add(makeHeader("ADMIN PANEL", "Election Control"), BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(2, 2, 15, 15));
        center.setBackground(CLR_BG);
        center.setBorder(new EmptyBorder(20, 40, 20, 40));

        JButton openBtn = makeButton("🟢 Open Election", CLR_GREEN, CLR_WHITE);
        openBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        openBtn.addActionListener(e -> {
            String id = JOptionPane.showInputDialog(this, "Enter Election ID to open:", "1");
            if (id != null) handleOpenElection(Integer.parseInt(id.trim()));
        });

        JButton closeBtn = makeButton("🔴 Close Election", CLR_RED, CLR_WHITE);
        closeBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        closeBtn.addActionListener(e -> handleCloseElection());

        JButton resultsBtn = makeButton("📊 View Results", CLR_BLUE, CLR_WHITE);
        resultsBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        resultsBtn.addActionListener(e -> handleViewResults());

        JButton auditBtn = makeButton("🔗 Audit Log", CLR_LBLUE, CLR_WHITE);
        auditBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        auditBtn.addActionListener(e -> handleViewAuditLog());

        center.add(openBtn);
        center.add(closeBtn);
        center.add(resultsBtn);
        center.add(auditBtn);
        p.add(center, BorderLayout.CENTER);

        JButton logoutBtn = makeButton("Logout", new Color(0x888888), CLR_WHITE);
        logoutBtn.addActionListener(e -> { adminToken = null; showScreen("IDLE"); });
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBackground(CLR_BG);
        bottomPanel.add(logoutBtn);
        p.add(bottomPanel, BorderLayout.SOUTH);
        return p;
    }

    // ═══════════════════════════════════════════════════════
    //  FLOW HANDLERS
    // ═══════════════════════════════════════════════════════

    private void bootMachine() {
        setStatus("Registering with server...");
        new Thread(() -> {
            try {
                boolean ok = api.registerMachine();
                SwingUtilities.invokeLater(() -> {
                    if (ok) {
                        setStatus("✅ Connected to " + api.getBaseUrl());
                        updateElectionInfo();
                        showScreen("IDLE");
                    } else {
                        updateLabel("bootStatus", "❌ Could not connect to server.\nCheck config.properties SERVER_URL");
                        setStatus("Connection failed. Retrying in 10s...");
                        Timer t = new Timer();
                        t.schedule(new TimerTask() {
                            public void run() { SwingUtilities.invokeLater(() -> bootMachine()); }
                        }, 10000);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    updateLabel("bootStatus", "❌ Server unreachable: " + ex.getMessage());
                    setStatus("Retrying in 10s...");
                    Timer t = new Timer();
                    t.schedule(new TimerTask() {
                        public void run() { SwingUtilities.invokeLater(() -> bootMachine()); }
                    }, 10000);
                });
            }
        }).start();
    }

    private void handleCardScan(String voterId) {
        setStatus("Verifying card...");
        new Thread(() -> {
            try {
                Map<String,Object> res = api.verifyCard(voterId);
                SwingUtilities.invokeLater(() -> {
                    if (!Boolean.TRUE.equals(res.get("success"))) {
                        showError((String) res.get("message"));
                        return;
                    }
                    if (Boolean.TRUE.equals(res.get("hasVoted"))) {
                        JOptionPane.showMessageDialog(this,
                            "Voter " + res.get("voterName") + " has already voted.",
                            "Already Voted", JOptionPane.WARNING_MESSAGE);
                        setStatus("Already voted. Ready for next voter.");
                        return;
                    }
                    currentVoterId   = (String) res.get("voterId");
                    currentVoterName = (String) res.get("voterName");
                    updateLabel("fpVoterLabel", "Voter: " + currentVoterName + " (" + currentVoterId + ")");
                    setStatus("Card verified. Please scan fingerprint.");
                    showScreen("FINGERPRINT");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Network error: " + ex.getMessage()));
            }
        }).start();
    }

    private void handleFingerprintVerify() {
        setStatus("Verifying fingerprint...");
        new Thread(() -> {
            try {
                // In real hardware: get fingerprintHash from scanner SDK
                // For demo: send a dummy hash — server always accepts it
                Map<String,Object> res = api.verifyFingerprint(currentVoterId, "DEMO-HASH-" + System.currentTimeMillis());
                SwingUtilities.invokeLater(() -> {
                    if (!Boolean.TRUE.equals(res.get("match"))) {
                        showError("Fingerprint mismatch. Please try again.");
                        return;
                    }
                    setStatus("Fingerprint verified. Starting session...");
                    startVotingSession();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Network error: " + ex.getMessage()));
            }
        }).start();
    }

    private void startVotingSession() {
        new Thread(() -> {
            try {
                Map<String,Object> res = api.startSession(currentVoterId);
                SwingUtilities.invokeLater(() -> {
                    if (!Boolean.TRUE.equals(res.get("success"))) {
                        showError((String) res.get("message"));
                        return;
                    }
                    currentSessionToken = (String) res.get("sessionToken");

                    // Get candidates from session response
                    Object rawCandidates = res.get("candidates");
                    if (rawCandidates instanceof List) {
                        currentCandidates = (List<Map<String,Object>>) rawCandidates;
                    }

                    buildCandidateButtons();
                    startSessionTimer(timeoutSeconds);
                    updateLabel("votingVoterInfo", "Voter: " + currentVoterName);
                    setStatus("Session started. " + currentCandidates.size() + " candidates. Voter may cast vote.");
                    showScreen("VOTING");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Could not start session: " + ex.getMessage()));
            }
        }).start();
    }

    private void buildCandidateButtons() {
        // Find candidatesPanel on votingScreen
        Component votingScreen = null;
        for (Component comp : mainPanel.getComponents()) {
            if (comp instanceof JPanel) {
                JScrollPane scroll = findByName((JPanel) comp, "candidatesScroll");
                if (scroll != null) {
                    JPanel panel = (JPanel) scroll.getViewport().getView();
                    panel.removeAll();

                    if (currentCandidates.isEmpty()) {
                        JLabel no = new JLabel("No candidates found for your constituency.");
                        no.setFont(new Font("Segoe UI", Font.ITALIC, 14));
                        no.setForeground(CLR_RED);
                        panel.add(no);
                    } else {
                        for (int i = 0; i < currentCandidates.size(); i++) {
                            Map<String,Object> candidate = currentCandidates.get(i);
                            int    cId      = Integer.parseInt(candidate.get("id").toString());
                            String cName    = (String) candidate.get("name");
                            String cParty   = (String) candidate.getOrDefault("party", "Independent");
                            int    slotNum  = i + 1;

                            JPanel row = buildCandidateRow(slotNum, cId, cName, cParty);
                            panel.add(row);
                            panel.add(Box.createVerticalStrut(8));
                        }
                    }
                    panel.revalidate();
                    panel.repaint();
                    break;
                }
            }
        }
    }

    private JPanel buildCandidateRow(int slot, int candidateId, String name, String party) {
        JPanel row = new JPanel(new BorderLayout(15, 0));
        row.setBackground(CLR_WHITE);
        row.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(new Color(0xDDE3EA), 1, true),
            new EmptyBorder(10, 15, 10, 15)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

        // Left: slot number (physical button label)
        JLabel slotLabel = new JLabel("BUTTON " + slot, SwingConstants.CENTER);
        slotLabel.setFont(new Font("Consolas", Font.BOLD, 13));
        slotLabel.setForeground(CLR_WHITE);
        slotLabel.setOpaque(true);
        slotLabel.setBackground(CLR_LBLUE);
        slotLabel.setPreferredSize(new Dimension(90, 50));
        slotLabel.setBorder(new EmptyBorder(5, 8, 5, 8));
        row.add(slotLabel, BorderLayout.WEST);

        // Center: candidate info
        JPanel info = new JPanel(new GridLayout(2, 1));
        info.setBackground(CLR_WHITE);
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        nameLabel.setForeground(CLR_DARK);
        JLabel partyLabel = new JLabel(party);
        partyLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        partyLabel.setForeground(new Color(0x666666));
        info.add(nameLabel);
        info.add(partyLabel);
        row.add(info, BorderLayout.CENTER);

        // Right: vote button (simulates physical LED button)
        JButton voteBtn = new JButton("VOTE");
        voteBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        voteBtn.setBackground(CLR_GREEN);
        voteBtn.setForeground(CLR_WHITE);
        voteBtn.setFocusPainted(false);
        voteBtn.setBorderPainted(false);
        voteBtn.setPreferredSize(new Dimension(90, 50));
        voteBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        voteBtn.addActionListener(e -> {
            selectedCandidateId   = candidateId;
            selectedCandidateName = name + " (" + party + ")";
            updateLabel("confirmCandidateLabel", selectedCandidateName);
            stopSessionTimer();
            showScreen("CONFIRM");
        });
        row.add(voteBtn, BorderLayout.EAST);
        return row;
    }

    private void handleCastVote() {
        setStatus("Recording vote on blockchain...");
        new Thread(() -> {
            try {
                Map<String,Object> res = api.castVote(currentSessionToken, selectedCandidateId);
                SwingUtilities.invokeLater(() -> {
                    if (!Boolean.TRUE.equals(res.get("success"))) {
                        showError((String) res.get("message"));
                        return;
                    }
                    String receipt = (String) res.getOrDefault("receiptHash", "N/A");
                    int block = res.containsKey("blockNumber") ? ((Number)res.get("blockNumber")).intValue() : 0;
                    updateLabel("receiptLabel",
                        "Receipt: " + receipt + "  |  Block #" + block);
                    showScreen("SUCCESS");
                    setStatus("Vote cast. Block #" + block + " added to blockchain.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Failed to cast vote: " + ex.getMessage()));
            }
        }).start();
    }

    private void handleAdminLogin(String username, String password) {
        setStatus("Authenticating admin...");
        new Thread(() -> {
            try {
                Map<String,Object> res = api.adminLogin(username, password);
                SwingUtilities.invokeLater(() -> {
                    if (!Boolean.TRUE.equals(res.get("success"))) {
                        JOptionPane.showMessageDialog(this,
                            res.get("message"), "Login Failed", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    adminToken = (String) res.get("adminToken");
                    setStatus("Admin logged in: " + username);
                    showScreen("ADMIN_PANEL");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError("Login error: " + ex.getMessage()));
            }
        }).start();
    }

    private void handleOpenElection(int electionId) {
        new Thread(() -> {
            try {
                Map<String,Object> res = api.openElection(electionId, adminToken);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, res.get("message"), "Election", JOptionPane.INFORMATION_MESSAGE);
                    updateElectionInfo();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex.getMessage()));
            }
        }).start();
    }

    private void handleCloseElection() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to close the election?\nVoting will be locked.",
            "Close Election", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        new Thread(() -> {
            try {
                Map<String,Object> status = api.getElectionStatus();
                Object elId = status.get("electionId");
                if (elId == null) {
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "No active election.", "Info", JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
                Map<String,Object> res = api.closeElection(((Number)elId).intValue(), adminToken);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, res.get("message"), "Election Closed", JOptionPane.INFORMATION_MESSAGE);
                    updateElectionInfo();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex.getMessage()));
            }
        }).start();
    }

    private void handleViewResults() {
        new Thread(() -> {
            try {
                Map<String,Object> res = api.getResults(adminToken);
                SwingUtilities.invokeLater(() -> {
                    JTextArea ta = new JTextArea(formatJson(res), 20, 50);
                    ta.setFont(new Font("Consolas", Font.PLAIN, 12));
                    ta.setEditable(false);
                    JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Election Results", JOptionPane.PLAIN_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex.getMessage()));
            }
        }).start();
    }

    private void handleViewAuditLog() {
        new Thread(() -> {
            try {
                Map<String,Object> res = api.getAuditLog(adminToken);
                SwingUtilities.invokeLater(() -> {
                    JTextArea ta = new JTextArea(formatJson(res), 20, 60);
                    ta.setFont(new Font("Consolas", Font.PLAIN, 11));
                    ta.setEditable(false);
                    JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Blockchain Audit Log", JOptionPane.PLAIN_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex.getMessage()));
            }
        }).start();
    }

    private void updateElectionInfo() {
        new Thread(() -> {
            try {
                Map<String,Object> status = api.getElectionStatus();
                SwingUtilities.invokeLater(() -> {
                    String info = Boolean.TRUE.equals(status.get("isActive"))
                        ? "Active: " + status.get("electionName") + " (" + status.get("electionType") + ")"
                        : "No active election";
                    updateLabel("electionInfoLabel", "Election Status: " + info);
                });
            } catch (Exception ignored) {}
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  SESSION TIMER
    // ═══════════════════════════════════════════════════════

    private void startSessionTimer(int seconds) {
        stopSessionTimer();
        final int[] remaining = {seconds};
        sessionTimer = new Timer();
        sessionTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                remaining[0]--;
                int min = remaining[0] / 60;
                int sec = remaining[0] % 60;
                String timeStr = String.format("%d:%02d", min, sec);
                Color c = remaining[0] <= 30 ? CLR_RED : CLR_AMBER;
                SwingUtilities.invokeLater(() -> {
                    updateTimerLabel("timerLabel", "Time remaining: " + timeStr, c);
                });
                if (remaining[0] <= 0) {
                    SwingUtilities.invokeLater(() -> {
                        stopSessionTimer();
                        new Thread(() -> {
                            try {
                                if (currentSessionToken != null)
                                    api.timeoutSession(currentSessionToken);
                            } catch (Exception ignored) {}
                        }).start();
                        currentSessionToken = null;
                        showScreen("IDLE");
                        setStatus("Session timed out after " + seconds + " seconds.");
                        JOptionPane.showMessageDialog(VotingMachineApp.this,
                            "Session timed out. Please start again.", "Timeout", JOptionPane.WARNING_MESSAGE);
                    });
                }
            }
        }, 1000, 1000);
    }

    private void stopSessionTimer() {
        if (sessionTimer != null) { sessionTimer.cancel(); sessionTimer = null; }
    }

    // ═══════════════════════════════════════════════════════
    //  UI HELPERS
    // ═══════════════════════════════════════════════════════

    private void showScreen(String name) {
        currentScreen = Screen.valueOf(name);
        cardLayout.show(mainPanel, name);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Error: " + message);
    }

    private void setStatus(String msg) {
        statusBar.setText(msg);
    }

    private void updateLabel(String name, String text) {
        for (Component comp : getAllComponents(mainPanel)) {
            if (name.equals(comp.getName()) && comp instanceof JLabel) {
                ((JLabel) comp).setText(text);
                return;
            }
        }
    }

    private void updateTimerLabel(String name, String text, Color color) {
        for (Component comp : getAllComponents(mainPanel)) {
            if (name.equals(comp.getName()) && comp instanceof JLabel) {
                JLabel l = (JLabel) comp;
                l.setText(text);
                l.setForeground(color);
                return;
            }
        }
    }

    private List<Component> getAllComponents(Container container) {
        List<Component> list = new ArrayList<>();
        for (Component c : container.getComponents()) {
            list.add(c);
            if (c instanceof Container) list.addAll(getAllComponents((Container) c));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> T findByName(Container container, String name) {
        for (Component c : getAllComponents(container)) {
            if (name.equals(c.getName())) return (T) c;
        }
        return null;
    }

    private JPanel makeHeader(String title, String subtitle) {
        JPanel h = new JPanel(new GridLayout(2, 1));
        h.setBackground(CLR_BLUE);
        h.setBorder(new EmptyBorder(14, 20, 14, 20));
        JLabel t = new JLabel(title, SwingConstants.CENTER);
        t.setFont(new Font("Segoe UI", Font.BOLD, 20));
        t.setForeground(CLR_WHITE);
        JLabel s = new JLabel(subtitle, SwingConstants.CENTER);
        s.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        s.setForeground(new Color(0xCCDDEE));
        h.add(t); h.add(s);
        return h;
    }

    private JButton makeButton(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new EmptyBorder(10, 20, 10, 20));
        return b;
    }

    private Border makeBorder(String label) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(label),
            new EmptyBorder(4, 8, 4, 8));
    }

    private String formatJson(Map<String,Object> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════
    //  MAIN
    // ═══════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        // Load config
        config = new Properties();
        try (InputStream is = VotingMachineApp.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (is != null) config.load(is);
        }

        String serverUrl     = config.getProperty("SERVER_URL",      "http://localhost:8080");
        String machineSecret = config.getProperty("MACHINE_SECRET",  "machine@evoting2025");
        String machineId     = config.getProperty("MACHINE_ID",      "PI-WARD-01");
        int    timeout       = Integer.parseInt(config.getProperty("SESSION_TIMEOUT_SECONDS", "120"));

        System.out.println("🖥  E-Voting Machine Client starting...");
        System.out.println("   Server URL: " + serverUrl);
        System.out.println("   Machine ID: " + machineId);

        api = new ApiClient(serverUrl, machineSecret, machineId);

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeLater(() -> {
            VotingMachineApp app = new VotingMachineApp();
            app.timeoutSeconds = timeout;
        });
    }
}

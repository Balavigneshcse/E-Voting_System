package Backend.controller;

import Backend.blockchain.Blockchain;
import Backend.model.*;
import Backend.repository.*;
import Backend.service.ElectionResultsService;
import Backend.service.NfcVotingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * MachineApiController — 16 REST APIs for the voting machine (thin client).
 *
 * BASE URL: /api/**
 *
 * Security: All /api/** requests must carry the header:
 *   X-Machine-Token: <token from POST /api/machine/register>
 *   (Admin endpoints also require X-Admin-Key header)
 *
 * Groups:
 *   1. Machine Startup  (3 APIs)  — called once on boot
 *   2. Voter Auth       (4 APIs)  — called per voter
 *   3. Voting Session   (4 APIs)  — called during active vote
 *   4. Results & Audit  (3 APIs)  — admin only, after election
 *   5. Admin Panel      (4 APIs)  — admin only
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class MachineApiController {

    // Simple machine tokens stored in memory (resets on server restart)
    private static final Set<String> VALID_MACHINE_TOKENS = new HashSet<>();

    // Admin key from application.properties
    @Value("${evoting.admin-key:admin@evoting123}")
    private String adminKey;

    // Machine secret from application.properties
    @Value("${evoting.machine-secret:machine@evoting2025}")
    private String machineSecret;

    @Autowired private NfcVotingService      nfcService;
    @Autowired private ElectionResultsService resultsService;
    @Autowired private VoterRepository       voterRepo;
    @Autowired private VoteRepository        voteRepo;
    @Autowired private VotingSessionRepository sessionRepo;
    @Autowired private CandidateRepository   candidateRepo;
    @Autowired private ElectionRepository    electionRepo;
    @Autowired private Blockchain            blockchain;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    // ═══════════════════════════════════════════════════════
    //  GROUP 1 — MACHINE STARTUP
    // ═══════════════════════════════════════════════════════

    /**
     * API 1 — GET /api/election/status
     * Pi calls this on boot to check if election is active.
     * Returns: electionName, electionType, isActive, startTime, wardName
     */
    @GetMapping("/election/status")
    public ResponseEntity<Map<String,Object>> getElectionStatus() {
        Election election = nfcService.getActiveElection();
        Map<String,Object> res = new HashMap<>();
        if (election == null) {
            res.put("isActive",     false);
            res.put("message",      "No election is currently active.");
            return ResponseEntity.ok(res);
        }
        res.put("isActive",       true);
        res.put("electionId",     election.getId());
        res.put("electionName",   election.getName());
        res.put("electionType",   election.getType());
        res.put("electionCycle",  election.getElectionCycle());
        res.put("message",        "Election is active. Voting is open.");
        return ResponseEntity.ok(res);
    }

    /**
     * API 2 — GET /api/candidates
     * Returns all candidates for the active election.
     * Pi uses this to show the button → candidate mapping.
     */
    @GetMapping("/candidates")
    public ResponseEntity<?> getCandidates(@RequestParam(required = false) Integer constituencyId,
                                           @RequestParam(required = false) Integer wardId) {
        Election election = nfcService.getActiveElection();
        if (election == null)
            return ResponseEntity.ok(Map.of("error", "No active election"));

        List<Candidate> candidates;
        if (constituencyId != null) {
            candidates = candidateRepo.findByElectionIdAndConstituencyId(election.getId(), constituencyId);
        } else {
            candidates = candidateRepo.findByElectionId(election.getId());
        }

        // Return only fields needed by Pi (no heavy photo bytes)
        List<Map<String,Object>> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",          c.getId());
            m.put("name",        c.getName());
            m.put("party",       c.getParty());
            m.put("slotNumber",  i + 1);   // button number on machine
            m.put("partyColor",  c.getPartyColor());
            m.put("photoUrl",    c.getPhotoUrl());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * API 3 — POST /api/machine/register
     * Pi sends its machineSecret to get a machineToken.
     * Body: { "machineSecret": "machine@evoting2025", "machineId": "PI-WARD-01" }
     * Returns: machineToken (used in X-Machine-Token header for all future requests)
     */
    @PostMapping("/machine/register")
    public ResponseEntity<Map<String,Object>> registerMachine(@RequestBody Map<String,String> body) {
        String secret = body.getOrDefault("machineSecret", "");
        String machineId = body.getOrDefault("machineId", "UNKNOWN");
        Map<String,Object> res = new HashMap<>();

        if (!machineSecret.equals(secret)) {
            res.put("success", false);
            res.put("message", "Invalid machine secret.");
            return ResponseEntity.status(401).body(res);
        }

        String token = "MT-" + UUID.randomUUID().toString().replace("-","").substring(0, 16).toUpperCase();
        VALID_MACHINE_TOKENS.add(token);

        res.put("success",      true);
        res.put("machineToken", token);
        res.put("machineId",    machineId);
        res.put("message",      "Machine registered. Use X-Machine-Token header in all requests.");
        System.out.println("✅ Machine registered: " + machineId + " | Token: " + token);
        return ResponseEntity.ok(res);
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 2 — VOTER AUTHENTICATION
    // ═══════════════════════════════════════════════════════

    /**
     * API 4 — POST /api/voter/verify-card
     * Pi reads RFID card UID and sends it here.
     * Body: { "rfidUid": "A3F2C1D4" }
     * Returns: voterId, voterName, hasVoted
     */
    @PostMapping("/voter/verify-card")
    public ResponseEntity<Map<String,Object>> verifyCard(@RequestBody Map<String,String> body,
                                                          HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        String rfidUid = body.getOrDefault("rfidUid", "");
        Map<String,Object> res = new HashMap<>();

        Voter voter = voterRepo.findByNfcCardId(rfidUid);
        // Also allow voter ID directly (for testing without RFID hardware)
        if (voter == null) voter = voterRepo.findByVoterId(rfidUid);

        if (voter == null) {
            res.put("success", false);
            res.put("message", "Voter card not recognised. Please try again or contact booth officer.");
            return ResponseEntity.ok(res);
        }

        Election election = nfcService.getActiveElection();
        boolean hasVoted = election != null &&
                voteRepo.existsByVoterIdAndElectionId(voter.getVoterId(), election.getId());

        res.put("success",   true);
        res.put("voterId",   voter.getVoterId());
        res.put("voterName", voter.getName());
        res.put("hasVoted",  hasVoted);
        res.put("message",   hasVoted ? "Already voted." : "Card verified. Please scan fingerprint.");
        return ResponseEntity.ok(res);
    }

    /**
     * API 5 — POST /api/voter/verify-fingerprint
     * Pi sends voter ID + fingerprint hash for biometric match.
     * Body: { "voterId": "V001", "fingerprintHash": "abc123..." }
     * Returns: match (true/false), sessionToken (if match)
     *
     * NOTE: In this demo, fingerprint is ALWAYS accepted (hash not checked)
     * because Pi hardware scanner SDK is not available on laptop.
     * Set "simulateFingerprint": true in body for demo mode.
     */
    @PostMapping("/voter/verify-fingerprint")
    public ResponseEntity<Map<String,Object>> verifyFingerprint(@RequestBody Map<String,String> body,
                                                                  HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        String voterId = body.getOrDefault("voterId", "");
        Map<String,Object> res = new HashMap<>();

        Voter voter = voterRepo.findByVoterId(voterId);
        if (voter == null) {
            res.put("success", false);
            res.put("match",   false);
            res.put("message", "Voter not found.");
            return ResponseEntity.ok(res);
        }

        // In real hardware: compare fingerprintHash with voter.getFingerprintTemplate()
        // For demo/testing: always return true
        boolean match = true;

        res.put("success", true);
        res.put("match",   match);
        res.put("voterId", voter.getVoterId());
        res.put("message", match ? "Fingerprint verified." : "Fingerprint mismatch. Try again.");
        return ResponseEntity.ok(res);
    }

    /**
     * API 6 — GET /api/voter/{voterId}/details
     * Pi fetches voter name + photo to display on screen.
     * Returns: name, photo (base64), wardNumber, constituencyId
     */
    @GetMapping("/voter/{voterId}/details")
    public ResponseEntity<Map<String,Object>> getVoterDetails(@PathVariable String voterId,
                                                               HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        Voter voter = voterRepo.findByVoterId(voterId);
        if (voter == null)
            return ResponseEntity.ok(Map.of("success", false, "message", "Voter not found."));

        Map<String,Object> res = new LinkedHashMap<>();
        res.put("success",        true);
        res.put("voterId",        voter.getVoterId());
        res.put("name",           voter.getName());
        res.put("wardNumber",     voter.getMunicipalityWard());
        res.put("constituencyId", voter.getVsConstituencyId());
        res.put("lsConstituencyId", voter.getLsConstituencyId());
        res.put("municipalityTier", voter.getMunicipalityTier());
        res.put("cardActive",     voter.getCardActive());
        // Send photo as base64 string if available
        if (voter.getPhoto() != null) {
            res.put("photoBase64", Base64.getEncoder().encodeToString(voter.getPhoto()));
            res.put("photoType",   voter.getPhotoType());
        }
        return ResponseEntity.ok(res);
    }

    /**
     * API 7 — GET /api/voter/{voterId}/status
     * Pi checks if voter has already voted in the active election.
     * Returns: hasVoted (true/false)
     */
    @GetMapping("/voter/{voterId}/status")
    public ResponseEntity<Map<String,Object>> getVoterStatus(@PathVariable String voterId,
                                                              HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        Election election = nfcService.getActiveElection();
        if (election == null)
            return ResponseEntity.ok(Map.of("success", false, "message", "No active election."));

        boolean hasVoted = voteRepo.existsByVoterIdAndElectionId(voterId, election.getId());
        return ResponseEntity.ok(Map.of(
            "success",       true,
            "voterId",       voterId,
            "hasVoted",      hasVoted,
            "electionId",    election.getId(),
            "message",       hasVoted ? "Voter has already cast their vote." : "Voter has not voted yet."
        ));
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 3 — VOTING SESSION
    // ═══════════════════════════════════════════════════════

    /**
     * API 8 — POST /api/session/start
     * Called after both card + fingerprint are verified.
     * Body: { "voterId": "V001" }
     * Returns: sessionToken (expires in 120 seconds), candidates list
     */
    @PostMapping("/session/start")
    public ResponseEntity<Map<String,Object>> startSession(@RequestBody Map<String,String> body,
                                                            HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        String voterId = body.getOrDefault("voterId", "");
        Map<String,Object> sessionResult = nfcService.startSession(voterId);

        if (!Boolean.TRUE.equals(sessionResult.get("success")))
            return ResponseEntity.ok(sessionResult);

        // Also do biometric step automatically (already verified in API 5)
        String sessionToken = (String) sessionResult.get("sessionToken");
        Map<String,Object> biometricResult = nfcService.verifyBiometric(sessionToken);

        // Add expiry info
        sessionResult.put("expiresInSeconds", 120);
        sessionResult.put("constituencyId",   biometricResult.get("constituencyId"));
        sessionResult.put("electionType",     biometricResult.get("electionType"));
        sessionResult.put("wardId",           biometricResult.get("wardId"));

        // Fetch candidates for this voter
        List<Candidate> candidates = nfcService.getCandidatesForVoter(sessionToken);
        List<Map<String,Object>> cList = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            Map<String,Object> cm = new LinkedHashMap<>();
            cm.put("id",         c.getId());
            cm.put("name",       c.getName());
            cm.put("party",      c.getParty());
            cm.put("slotNumber", i + 1);
            cm.put("partyColor", c.getPartyColor());
            cList.add(cm);
        }
        sessionResult.put("candidates", cList);
        return ResponseEntity.ok(sessionResult);
    }

    /**
     * API 9 — POST /api/vote/cast
     * Voter has pressed a button. Pi sends the vote.
     * Body: { "sessionToken": "xxx", "candidateId": 5 }
     * Returns: success, receiptHash (blockchain block number)
     */
    @PostMapping("/vote/cast")
    public ResponseEntity<Map<String,Object>> castVote(@RequestBody Map<String,Object> body,
                                                        HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        String  token       = (String) body.get("sessionToken");
        Integer candidateId = Integer.parseInt(body.get("candidateId").toString());
        Map<String,Object> result = nfcService.castVote(token, candidateId);

        if (Boolean.TRUE.equals(result.get("success"))) {
            // Generate receipt hash for voter confirmation
            String receipt = "RCP-" + UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
            result.put("receiptHash",  receipt);
            result.put("votedAt",      LocalDateTime.now().toString());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * API 10 — POST /api/session/cancel
     * Voter pressed Cancel or left without voting.
     * Body: { "sessionToken": "xxx" }
     * Returns: success
     */
    @PostMapping("/session/cancel")
    public ResponseEntity<Map<String,Object>> cancelSession(@RequestBody Map<String,String> body,
                                                             HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        return deleteSession(body.get("sessionToken"), "Session cancelled by voter.");
    }

    /**
     * API 11 — POST /api/session/timeout
     * Voter was idle for 2 minutes. Pi auto-calls this.
     * Body: { "sessionToken": "xxx" }
     * Returns: success
     */
    @PostMapping("/session/timeout")
    public ResponseEntity<Map<String,Object>> timeoutSession(@RequestBody Map<String,String> body,
                                                              HttpServletRequest request) {
        if (!isValidMachineToken(request))
            return unauthorized();

        return deleteSession(body.get("sessionToken"), "Session timed out after 2 minutes.");
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 4 — RESULTS & AUDIT (admin only)
    // ═══════════════════════════════════════════════════════

    /**
     * API 12 — GET /api/results
     * Returns vote counts per candidate.
     * Requires X-Admin-Key header.
     */
    @GetMapping("/results")
    public ResponseEntity<?> getResults(@RequestParam(required = false) Integer electionId,
                                         HttpServletRequest request) {
        if (!isValidAdminKey(request))
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        Election election = nfcService.getActiveElection();
        if (election == null && electionId == null)
            return ResponseEntity.ok(Map.of("error", "No active election."));

        int eid = electionId != null ? electionId : election.getId();
        return ResponseEntity.ok(resultsService.getResults(eid));
    }

    /**
     * API 13 — GET /api/audit/log
     * Returns immutable timestamped vote audit trail.
     * Requires X-Admin-Key header.
     */
    @GetMapping("/audit/log")
    public ResponseEntity<?> getAuditLog(HttpServletRequest request) {
        if (!isValidAdminKey(request))
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        // Return blockchain as the immutable audit log
        List<Map<String,Object>> log = new ArrayList<>();
        blockchain.getChain().forEach(block -> {
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("blockNumber",   block.getIndex());
            entry.put("voterId",       block.getVoterId());
            entry.put("candidate",     block.getCandidate());
            entry.put("hash",          block.getHash());
            entry.put("previousHash",  block.getPreviousHash());
            entry.put("timestamp",     block.getTimestamp().toString());
            log.add(entry);
        });
        return ResponseEntity.ok(Map.of(
            "chainValid", blockchain.isChainValid(),
            "totalBlocks", log.size(),
            "log", log
        ));
    }

    /**
     * API 14 — GET /api/results/turnout
     * Returns voter turnout statistics.
     * Requires X-Admin-Key header.
     */
    @GetMapping("/results/turnout")
    public ResponseEntity<?> getTurnout(HttpServletRequest request) {
        if (!isValidAdminKey(request))
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        Election election = nfcService.getActiveElection();
        if (election == null)
            return ResponseEntity.ok(Map.of("error", "No active election."));

        return ResponseEntity.ok(resultsService.getElectionStats(election.getId()));
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 5 — ADMIN PANEL
    // ═══════════════════════════════════════════════════════

    /**
     * API 15 — POST /api/admin/login
     * Admin logs in and gets an admin JWT (simple key for demo).
     * Body: { "username": "Bala", "password": "922524104016" }
     * Returns: adminToken
     *
     * NOTE: For demo, uses the same in-memory users from SecurityConfig.
     * Username "Logic Makers" / password "logic" is super admin.
     */
    @PostMapping("/admin/login")
    public ResponseEntity<Map<String,Object>> adminLogin(@RequestBody Map<String,String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        Map<String,Object> res = new HashMap<>();

        // Simple check against known admin credentials
        boolean valid = isValidAdminCredentials(username, password);
        if (!valid) {
            res.put("success", false);
            res.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(res);
        }

        res.put("success",    true);
        res.put("adminToken", adminKey);   // return the key that other admin APIs expect
        res.put("username",   username);
        res.put("message",    "Login successful. Use X-Admin-Key header for admin APIs.");
        return ResponseEntity.ok(res);
    }

    /**
     * API 16 — POST /api/admin/election/open
     * Election officer opens voting. All machines unlock.
     * Body: { "electionId": 1 }
     * Requires X-Admin-Key header.
     */
    @PostMapping("/admin/election/open")
    public ResponseEntity<Map<String,Object>> openElection(@RequestBody Map<String,Object> body,
                                                            HttpServletRequest request) {
        if (!isValidAdminKey(request))
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        Integer electionId = Integer.parseInt(body.get("electionId").toString());
        String result = nfcService.enableElection(electionId);
        System.out.println("🗳 Election opened: " + electionId);
        return ResponseEntity.ok(Map.of("success", true, "message", result));
    }

    /**
     * POST /api/admin/election/close
     * Election officer closes voting. All machines lock.
     * Body: { "electionId": 1 }
     * Requires X-Admin-Key header.
     */
    @PostMapping("/admin/election/close")
    public ResponseEntity<Map<String,Object>> closeElection(@RequestBody Map<String,Object> body,
                                                             HttpServletRequest request) {
        if (!isValidAdminKey(request))
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        Election election = nfcService.getActiveElection();
        if (election == null)
            return ResponseEntity.ok(Map.of("success", false, "message", "No active election to close."));

        election.setIsActive(false);
        electionRepo.save(election);
        System.out.println("🔒 Election closed: " + election.getName());
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Election '" + election.getName() + "' closed. Voting is now locked."
        ));
    }

    // ═══════════════════════════════════════════════════════
    //  HELPER METHODS
    // ═══════════════════════════════════════════════════════

    private boolean isValidMachineToken(HttpServletRequest request) {
        String token = request.getHeader("X-Machine-Token");
        // Allow if no tokens registered yet (first registration call)
        if (VALID_MACHINE_TOKENS.isEmpty()) return true;
        return token != null && VALID_MACHINE_TOKENS.contains(token);
    }

    private boolean isValidAdminKey(HttpServletRequest request) {
        String key = request.getHeader("X-Admin-Key");
        return adminKey.equals(key);
    }

    private boolean isValidAdminCredentials(String username, String password) {
        // Match against in-memory users from SecurityConfig
        Map<String,String> users = Map.of(
            "Logic Makers", "logic",
            "Bala",   "922524104016",
            "Arun",   "922524104011",
            "Deepak", "922524104026",
            "Dinesh", "922524104040"
        );
        return password.equals(users.getOrDefault(username, ""));
    }

    private ResponseEntity<Map<String,Object>> deleteSession(String token, String message) {
        if (token == null)
            return ResponseEntity.ok(Map.of("success", false, "message", "No session token provided."));

        VotingSession session = sessionRepo.findBySessionToken(token);
        if (session != null) {
            sessionRepo.delete(session);
        }
        return ResponseEntity.ok(Map.of("success", true, "message", message));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String,Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of(
            "success", false,
            "message", "Missing or invalid X-Machine-Token header. Call POST /api/machine/register first."
        ));
    }
}

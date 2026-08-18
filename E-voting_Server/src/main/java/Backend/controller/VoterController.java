package Backend.controller;

import Backend.dto.FingerprintResult;
import Backend.dto.VoterCardResult;
import Backend.model.Election;
import Backend.model.Voter;
import Backend.security.MachineRequestContext;
import Backend.service.VotingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Group 2 of the machine API: identifying the voter.
 *
 * <p>Four endpoints, matching the quotation's VoterController deliverable: card read,
 * fingerprint check, voter details for the screen, and already-voted status.
 */
@RestController
@RequestMapping("/api/voter")
public class VoterController {

    private final VotingService voting;

    public VoterController(VotingService voting) {
        this.voting = voting;
    }

    /**
     * API 4 — resolves a tapped card to a voter.
     *
     * <p>Accepts the NFC UID or the voter ID, which is what makes the simulated reader
     * work without changing the endpoint when real hardware arrives.
     */
    @PostMapping("/verify-card")
    public ResponseEntity<VoterCardResult> verifyCard(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String cardId = body.getOrDefault("rfidUid", body.get("cardId"));
        return ResponseEntity.ok(
                voting.verifyCard(cardId, MachineRequestContext.requireMachineId(request)));
    }

    /**
     * API 5 — checks a fingerprint sample and, on a match, returns the single-use token
     * that {@code /api/session/start} requires.
     *
     * <p>The terminal cannot assert that a fingerprint matched; it submits a sample and
     * is told. That is the difference from the previous implementation, where a client
     * flag was enough and the session marked itself verified regardless.
     */
    @PostMapping("/verify-fingerprint")
    public ResponseEntity<FingerprintResult> verifyFingerprint(@RequestBody Map<String, String> body,
                                                               HttpServletRequest request) {
        String voterId = body.get("voterId");
        String sample  = body.getOrDefault("fingerprintSample", body.get("fingerprintHash"));

        if (voterId == null || voterId.isBlank()) {
            return ResponseEntity.badRequest().body(FingerprintResult.fail("voterId is required."));
        }
        return ResponseEntity.ok(voting.verifyFingerprint(
                voterId, sample, MachineRequestContext.requireMachineId(request)));
    }

    /** API 6 — name and photo, for the confirmation screen the voter sees before voting. */
    @GetMapping("/{voterId}/details")
    public ResponseEntity<Map<String, Object>> details(@PathVariable String voterId) {
        Optional<Voter> found = voting.lookupVoter(voterId);
        if (found.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Voter not found."));
        }
        Voter voter = found.get();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success",           true);
        response.put("voterId",           voter.getVoterId());
        response.put("name",              voter.getName());
        response.put("constituencyId",    voter.getVsConstituencyId());
        response.put("constituencyName",  voting.constituencyName(voter.getVsConstituencyId()));
        response.put("lsConstituencyId",  voter.getLsConstituencyId());
        response.put("lsConstituencyName", voting.constituencyName(voter.getLsConstituencyId()));
        response.put("cardActive",        voter.getCardActive());
        response.put("fingerprintEnrolled", voter.getFingerprintEnrolled());
        if (voter.getPhoto() != null) {
            response.put("photoBase64", Base64.getEncoder().encodeToString(voter.getPhoto()));
            response.put("photoType",   voter.getPhotoType());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * API 7 — has this voter already voted in the open election.
     *
     * <p>Answered from {@code voter_turnout}, which records that a voter voted but not
     * what they chose. Asking this question cannot leak a ballot.
     */
    @GetMapping("/{voterId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String voterId) {
        Optional<Election> active = voting.activeElection();
        if (active.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Polling is closed."));
        }
        boolean hasVoted = voting.hasVoted(voterId, active.get().getId());
        return ResponseEntity.ok(Map.of(
                "success",    true,
                "voterId",    voterId,
                "electionId", active.get().getId(),
                "hasVoted",   hasVoted,
                "message",    hasVoted ? "Already voted." : "Eligible to vote."));
    }
}

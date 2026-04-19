package Backend.controller;

import Backend.model.Candidate;
import Backend.model.Election;
import Backend.service.NfcVotingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin("*")
public class NfcVotingController {

    @Autowired private NfcVotingService service;

    @PostMapping("/nfc/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, String> body) {
        try { return ResponseEntity.ok(service.startSession(body.get("voterIdentifier"))); }
        catch (Exception e) { return ResponseEntity.ok(err("Server error: " + e.getMessage())); }
    }

    @PostMapping("/nfc/biometric")
    public ResponseEntity<Map<String, Object>> biometric(@RequestBody Map<String, String> body) {
        try { return ResponseEntity.ok(service.verifyBiometric(body.get("sessionToken"))); }
        catch (Exception e) { return ResponseEntity.ok(err("Server error: " + e.getMessage())); }
    }

    // Candidates now fetched by session token — service picks right list based on election type
    @GetMapping("/nfc/candidates")
    public List<Candidate> candidates(@RequestParam String sessionToken) {
        return service.getCandidatesForVoter(sessionToken);
    }

    // Keep old endpoint for compatibility
    @GetMapping("/candidates/constituency/{id}")
    public List<Candidate> candidatesByConstituency(@PathVariable Integer id) {
        return service.getCandidatesForVoter(null);
    }

    @PostMapping("/nfc/vote")
    public ResponseEntity<Map<String, Object>> vote(@RequestBody Map<String, Object> body) {
        try {
            String  token       = (String) body.get("sessionToken");
            Integer candidateId = Integer.parseInt(body.get("candidateId").toString());
            return ResponseEntity.ok(service.castVote(token, candidateId));
        } catch (Exception e) { return ResponseEntity.ok(err("Server error: " + e.getMessage())); }
    }

    // Admin endpoints
    @GetMapping("/admin/elections")
    public List<Election> getAllElections() {
        return service.getAllElections();
    }

    @PostMapping("/admin/enable-election/{id}")
    public String enableElection(@PathVariable Integer id) {
        return service.enableElection(id);
    }

    @PostMapping("/admin/reset-election/{id}")
    public String resetElection(@PathVariable Integer id) {
        return service.resetElection(id);
    }

    @PostMapping("/admin/reset-election")
    public String resetCurrentElection() {
        Election active = service.getActiveElection();
        if (active == null) return "No active election.";
        return service.resetElection(active.getId());
    }

    @GetMapping("/admin/active-election")
    public Election activeElection() {
        return service.getActiveElection();
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("message", msg);
        return m;
    }
}
package Backend.controller;

import Backend.dto.CandidateOption;
import Backend.model.Election;
import Backend.security.MachineRequestContext;
import Backend.service.MachineService;
import Backend.service.VotingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Group 1 of the machine API: what a terminal calls on boot.
 *
 * <p>Three endpoints, matching the quotation's ElectionController deliverable:
 * registration, election status, and the candidate list.
 *
 * <p>Ordering note: a terminal must call {@code /api/machine/register} first. Every
 * other endpoint requires the credentials that registration returns. The previous
 * client checked election status before registering, which only worked because the
 * endpoint was unauthenticated.
 */
@RestController
@RequestMapping("/api")
public class ElectionController {

    private final VotingService  voting;
    private final MachineService machines;

    public ElectionController(VotingService voting, MachineService machines) {
        this.voting   = voting;
        this.machines = machines;
    }

    /**
     * API 1 — registers this terminal and returns its working credentials.
     *
     * <p>The only unsigned endpoint, because the terminal has no signing key until this
     * call hands one over. Authenticated instead by the one-time provisioning secret,
     * and the response carries a signing key, so this must not be served over plain HTTP.
     */
    @PostMapping("/machine/register")
    public ResponseEntity<MachineService.RegistrationOutcome> register(
            @RequestBody Map<String, String> body) {

        MachineService.RegistrationOutcome outcome = machines.register(
                body.get("machineId"),
                body.getOrDefault("provisioningSecret", body.get("machineSecret")));

        return outcome.success()
                ? ResponseEntity.ok(outcome)
                : ResponseEntity.status(401).body(outcome);
    }

    /** API 2 — is polling open, and for which election. */
    @GetMapping("/election/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("machineId", MachineRequestContext.requireMachineId(request));

        Optional<Election> active = voting.activeElection();
        if (active.isEmpty()) {
            response.put("isActive", false);
            response.put("message", "Polling is closed. Waiting for the election officer.");
            return ResponseEntity.ok(response);
        }

        Election election = active.get();
        response.put("isActive",      true);
        response.put("electionId",    election.getId());
        response.put("electionName",  election.getName());
        response.put("electionNameTa", election.getNameTa());
        response.put("electionType",  election.getType());
        response.put("electionCycle", election.getElectionCycle());
        response.put("message",       "Polling is open.");
        return ResponseEntity.ok(response);
    }

    /**
     * API 3 — the ballot for a constituency.
     *
     * <p>Used for pre-loading and for the officer's verification screen. The ballot a
     * voter actually sees comes from {@code /api/session/start}, derived from their own
     * registration rather than from anything the terminal supplies.
     */
    @GetMapping("/candidates")
    public ResponseEntity<Map<String, Object>> candidates(
            @RequestParam(required = false) Integer constituencyId) {

        Optional<Election> active = voting.activeElection();
        if (active.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Polling is closed.",
                    "candidates", List.of()));
        }
        if (constituencyId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "constituencyId is required.",
                    "candidates", List.of()));
        }

        List<CandidateOption> ballot = voting.ballotFor(active.get(), constituencyId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "electionId", active.get().getId(),
                "constituencyId", constituencyId,
                "maxSlots", VotingService.MAX_BALLOT_SLOTS,
                "candidates", ballot));
    }
}

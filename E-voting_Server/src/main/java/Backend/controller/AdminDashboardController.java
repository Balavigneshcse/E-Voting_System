package Backend.controller;

import Backend.ledger.LedgerValidation;
import Backend.ledger.VoteLedger;
import Backend.model.Election;
import Backend.model.Machine;
import Backend.service.ElectionAdminService;
import Backend.service.MachineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Terminal registry, ledger audit and polling controls for the browser dashboard.
 *
 * <p>Session-authenticated and role-gated by {@code SecurityConfig}. This is where an
 * election officer adds a booth terminal, issues its provisioning secret, and revokes it
 * if it goes missing — operations that previously had no interface at all, because every
 * terminal shared one secret baked into a properties file.
 */
@RestController
@RequestMapping("/admin")
public class AdminDashboardController {

    private final MachineService       machines;
    private final ElectionAdminService elections;
    private final VoteLedger           ledger;

    public AdminDashboardController(MachineService machines,
                                    ElectionAdminService elections,
                                    VoteLedger ledger) {
        this.machines  = machines;
        this.elections = elections;
        this.ledger    = ledger;
    }

    // ── Elections ───────────────────────────────────────────────────────────

    @GetMapping("/elections")
    public List<Map<String, Object>> listElections() {
        return elections.listSupportedElections().stream()
                .map(AdminDashboardController::describeElection)
                .toList();
    }

    @PostMapping("/elections/{electionId}/open")
    public ResponseEntity<?> openElection(@PathVariable Integer electionId) {
        return ResponseEntity.ok(elections.open(electionId));
    }

    @PostMapping("/elections/{electionId}/close")
    public ResponseEntity<?> closeElection(@PathVariable Integer electionId) {
        return ResponseEntity.ok(elections.close(electionId));
    }

    /** Starts the next cycle for a type (PM or CM) and opens it. */
    @PostMapping("/elections/next/{type}")
    public ResponseEntity<?> nextElection(@PathVariable String type) {
        return ResponseEntity.ok(elections.nextElection(type.toUpperCase()));
    }

    /** Which states a CM election is currently open to. */
    @GetMapping("/elections/{electionId}/open-states")
    public List<Map<String, Object>> openStates(@PathVariable Integer electionId) {
        return elections.openStates(electionId);
    }

    @PostMapping("/elections/{electionId}/open-states/{stateId}")
    public ResponseEntity<?> openState(@PathVariable Integer electionId, @PathVariable Integer stateId) {
        return ResponseEntity.ok(elections.openState(electionId, stateId));
    }

    @DeleteMapping("/elections/{electionId}/open-states/{stateId}")
    public ResponseEntity<?> closeState(@PathVariable Integer electionId, @PathVariable Integer stateId) {
        return ResponseEntity.ok(elections.closeState(electionId, stateId));
    }

    // ── Terminals ───────────────────────────────────────────────────────────

    @GetMapping("/machines")
    public List<Map<String, Object>> listMachines() {
        return machines.listAll().stream()
                .map(AdminDashboardController::describeMachine)
                .toList();
    }

    @PostMapping("/machines")
    public ResponseEntity<?> createMachine(@RequestBody Map<String, String> body) {
        String machineId = trimmed(body.get("machineId"));
        String label     = trimmed(body.get("label"));
        if (machineId == null || label == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "machineId and label are both required."));
        }
        try {
            Machine created = machines.create(machineId, label, trimmed(body.get("boothName")));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "machine", describeMachine(created),
                    "message", "Terminal added. Issue its provisioning secret next."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Issues a fresh provisioning secret for a terminal.
     *
     * <p>The secret is returned once and cannot be retrieved again — only its PBKDF2
     * verifier is kept. Calling this on an already-registered terminal rotates its
     * signing key and invalidates its current tokens, which is how a lost terminal is
     * taken out of service.
     */
    @PostMapping("/machines/{machineId}/provision")
    public ResponseEntity<?> provisionMachine(@PathVariable String machineId) {
        try {
            String secret = machines.reprovision(machineId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "machineId", machineId,
                    "provisioningSecret", secret,
                    "message", "Copy this secret into the terminal's config now. "
                            + "It will not be shown again."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/machines/{machineId}/revoke")
    public ResponseEntity<?> revokeMachine(@PathVariable String machineId) {
        try {
            machines.revoke(machineId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Terminal revoked. Its tokens stopped working immediately."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    // ── Ledger ──────────────────────────────────────────────────────────────

    /** Recomputes the whole chain and reports whether any recorded vote was altered. */
    @GetMapping("/ledger/validate")
    public Map<String, Object> validateLedger() {
        LedgerValidation validation = ledger.validate();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid",         validation.valid());
        response.put("totalBlocks",   validation.totalBlocks());
        response.put("firstBadIndex", validation.firstBadIndex());
        response.put("message",       validation.message());
        return response;
    }

    @GetMapping("/ledger")
    public Map<String, Object> ledger() {
        LedgerValidation validation = ledger.validate();
        return Map.of(
                "valid",       validation.valid(),
                "totalBlocks", validation.totalBlocks(),
                "message",     validation.message(),
                "blocks",      ledger.fullChain().stream().map(ResultsController::describe).toList());
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private static Map<String, Object> describeMachine(Machine machine) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("machineId",   machine.getMachineId());
        summary.put("label",       machine.getLabel());
        summary.put("boothName",   machine.getBoothName());
        summary.put("status",      machine.getStatus());
        summary.put("provisioned", machine.isProvisioned());
        summary.put("registeredAt", machine.getRegisteredAt());
        summary.put("lastSeenAt",  machine.getLastSeenAt());
        summary.put("revokedAt",   machine.getRevokedAt());
        return summary;
    }

    private static Map<String, Object> describeElection(Election election) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id",       election.getId());
        summary.put("name",     election.getName());
        summary.put("nameTa",   election.getNameTa());
        summary.put("type",     election.getType());
        summary.put("isActive", election.getIsActive());
        return summary;
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

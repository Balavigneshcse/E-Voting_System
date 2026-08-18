package Backend.controller;

import Backend.dto.SimpleResult;
import Backend.model.Election;
import Backend.security.AdminKeyGuard;
import Backend.security.MachineRequestContext;
import Backend.service.ElectionAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Group 5 of the machine API: election officer controls available at the terminal.
 *
 * <p>Matches the quotation's AdminController deliverable, with two deliberate omissions.
 *
 * <p>There is no {@code POST /api/admin/login}. Administrators authenticate through the
 * browser dashboard's session login. The old client still called this endpoint after it
 * had been deleted from the server, so the terminal's admin panel silently did not work.
 *
 * <p>There is no voter registration endpoint here either. Enrolling voters means handling
 * identity documents, photographs and biometrics, which belongs on the supervised admin
 * dashboard rather than on a terminal standing in a polling booth.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ElectionAdminService elections;
    private final AdminKeyGuard        adminKey;

    public AdminController(ElectionAdminService elections, AdminKeyGuard adminKey) {
        this.elections = elections;
        this.adminKey  = adminKey;
    }

    /** Elections the officer may open, PM and CM only. */
    @GetMapping("/elections")
    public ResponseEntity<?> list(HttpServletRequest request) {
        if (!adminKey.isAuthorised(request)) {
            return forbidden();
        }
        List<Map<String, Object>> summary = elections.listSupportedElections().stream()
                .map(AdminController::describe)
                .toList();
        return ResponseEntity.ok(Map.of("success", true, "elections", summary));
    }

    /** Opens polling. Every terminal picks this up on its next status poll. */
    @PostMapping("/election/open")
    public ResponseEntity<?> open(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!adminKey.isAuthorised(request)) {
            return forbidden();
        }
        Integer electionId = readElectionId(body);
        if (electionId == null) {
            return ResponseEntity.badRequest().body(SimpleResult.fail("electionId is required."));
        }
        log.info("Terminal {} requested polling open for election {}.",
                MachineRequestContext.requireMachineId(request), electionId);
        return ResponseEntity.ok(elections.open(electionId));
    }

    /** Closes polling. Recorded votes stay exactly as they are. */
    @PostMapping("/election/close")
    public ResponseEntity<?> close(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!adminKey.isAuthorised(request)) {
            return forbidden();
        }
        Integer electionId = readElectionId(body);
        if (electionId == null) {
            return ResponseEntity.badRequest().body(SimpleResult.fail("electionId is required."));
        }
        log.info("Terminal {} requested polling close for election {}.",
                MachineRequestContext.requireMachineId(request), electionId);
        return ResponseEntity.ok(elections.close(electionId));
    }

    private static Integer readElectionId(Map<String, Object> body) {
        Object raw = body.get("electionId");
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> describe(Election election) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id",       election.getId());
        summary.put("name",     election.getName());
        summary.put("nameTa",   election.getNameTa());
        summary.put("type",     election.getType());
        summary.put("isActive", election.getIsActive());
        return summary;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of(
                "success", false,
                "message", "Election officer authorisation is required."));
    }
}

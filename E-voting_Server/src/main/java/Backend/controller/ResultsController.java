package Backend.controller;

import Backend.ledger.LedgerBlock;
import Backend.ledger.LedgerValidation;
import Backend.ledger.VoteLedger;
import Backend.model.Election;
import Backend.security.AdminKeyGuard;
import Backend.service.ElectionResultsService;
import Backend.service.VotingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Group 4 of the machine API: results and audit.
 *
 * <p>Three endpoints, matching the quotation's ResultsController deliverable. All three
 * additionally require the election officer's key on top of machine authentication, so a
 * terminal on its own cannot read running totals.
 */
@RestController
@RequestMapping("/api")
public class ResultsController {

    private final ElectionResultsService results;
    private final VotingService          voting;
    private final VoteLedger             ledger;
    private final AdminKeyGuard          adminKey;

    public ResultsController(ElectionResultsService results,
                             VotingService voting,
                             VoteLedger ledger,
                             AdminKeyGuard adminKey) {
        this.results  = results;
        this.voting   = voting;
        this.ledger   = ledger;
        this.adminKey = adminKey;
    }

    /** API 12 — live tally, recomputed from the ballots table on every call. */
    @GetMapping("/results")
    public ResponseEntity<?> results(@RequestParam(required = false) Integer electionId,
                                     HttpServletRequest request) {
        if (!adminKey.isAuthorised(request)) {
            return forbidden();
        }
        Integer target = electionId != null
                ? electionId
                : voting.activeElection().map(Election::getId).orElse(null);
        if (target == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "No election selected."));
        }
        return ResponseEntity.ok(results.getResults(target));
    }

    /**
     * API 13 — the ledger, for verifying that no recorded vote has been altered.
     *
     * <p>Every entry is anonymous. The previous audit log emitted {@code voterId} beside
     * the candidate for each block, which meant the transparency mechanism itself
     * published how each person voted.
     */
    @GetMapping("/audit/log")
    public ResponseEntity<?> auditLog(HttpServletRequest request) {
        if (!adminKey.isAuthorised(request)) {
            return forbidden();
        }
        LedgerValidation validation = ledger.validate();

        List<Map<String, Object>> entries = ledger.fullChain().stream()
                .map(ResultsController::describe)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chainValid",    validation.valid());
        response.put("totalBlocks",   validation.totalBlocks());
        response.put("firstBadIndex", validation.firstBadIndex());
        response.put("message",       validation.message());
        response.put("log",           entries);
        return ResponseEntity.ok(response);
    }

    /** API 14 — turnout and terminal health. */
    @GetMapping("/results/turnout")
    public ResponseEntity<?> turnout(HttpServletRequest request) {
        if (!adminKey.isAuthorised(request)) {
            return forbidden();
        }
        Optional<Election> active = voting.activeElection();
        if (active.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Polling is closed."));
        }
        return ResponseEntity.ok(results.getElectionStats(active.get().getId()));
    }

    static Map<String, Object> describe(LedgerBlock block) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("blockNumber",    block.getBlockIndex());
        entry.put("ballotId",       block.getBallotUuid());
        entry.put("electionId",     block.getElectionId());
        entry.put("candidateId",    block.getCandidateId());
        entry.put("constituencyId", block.getConstituencyId());
        entry.put("machineId",      block.getMachineId());
        entry.put("castAtHour",     block.getCastAtHour());
        entry.put("hash",           block.getHash());
        entry.put("previousHash",   block.getPreviousHash());
        entry.put("hashVerified",   block.getHash().equals(block.computeHash()));
        return entry;
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of(
                "success", false,
                "message", "Election officer authorisation is required."));
    }
}

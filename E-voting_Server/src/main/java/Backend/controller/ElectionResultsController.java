package Backend.controller;

import Backend.service.ElectionResultsService;
import Backend.service.ResultsExportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Results and settings for the browser admin dashboard.
 *
 * <p>Session-authenticated and role-gated by {@code SecurityConfig}. The wide-open
 * {@code @CrossOrigin("*")} that used to sit here has been removed: the dashboard is
 * served from the same origin, so allowing any site to call these endpoints only widened
 * the attack surface.
 *
 * <p>The municipality endpoints are gone along with the rest of the four-tier voting
 * feature.
 */
@RestController
public class ElectionResultsController {

    private final ElectionResultsService results;
    private final ResultsExportService   export;

    public ElectionResultsController(ElectionResultsService results, ResultsExportService export) {
        this.results = results;
        this.export  = export;
    }

    /** Full live picture for one election: totals, turnout, per-constituency leaders. */
    @GetMapping("/admin/results/{electionId}")
    public Map<String, Object> getResults(@PathVariable Integer electionId) {
        return results.getResults(electionId);
    }

    /** The same results as a spreadsheet — summary, party totals, constituency leaders,
     *  and a full state/district/constituency breakdown, one sheet each. */
    @GetMapping("/admin/results/{electionId}/export")
    public ResponseEntity<byte[]> exportResults(@PathVariable Integer electionId) throws IOException {
        byte[] workbook = export.export(electionId);
        Map<String, Object> summary = results.getResults(electionId);
        Object electionObj = summary.get("election");
        String name = electionObj instanceof Map<?, ?> election && election.get("name") instanceof String n
                ? n.replaceAll("[^a-zA-Z0-9 _-]", "").replace(' ', '_')
                : ("election-" + electionId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name + "_results.xlsx").build().toString())
                .body(workbook);
    }

    @GetMapping("/admin/results/{electionId}/state/{stateId}")
    public List<Map<String, Object>> getStateResults(@PathVariable Integer electionId,
                                                     @PathVariable Integer stateId) {
        Map<String, Object> summary = results.getResults(electionId);
        String type = "CM";
        if (summary.get("election") instanceof Map<?, ?> election
                && election.get("type") instanceof String electionType) {
            type = electionType;
        }
        return "PM".equals(type)
                ? results.getPmStateResults(electionId, stateId)
                : results.getCmStateResults(electionId, stateId);
    }

    @GetMapping("/admin/results/{electionId}/states")
    public List<Map<String, Object>> getStatesWithVotes(@PathVariable Integer electionId) {
        return results.getStatesWithVotes(electionId);
    }

    @GetMapping("/admin/results/{electionId}/parties")
    public List<Map<String, Object>> getPartyTotals(@PathVariable Integer electionId) {
        return results.getPartyTotals(electionId);
    }

    @GetMapping("/admin/results/{electionId}/leaders")
    public List<Map<String, Object>> getConstituencyLeaders(@PathVariable Integer electionId) {
        return results.getConstituencyLeaders(electionId);
    }

    /** Party-wise seat performance — see {@link ElectionResultsService#getPartySeatTally}. */
    @GetMapping("/admin/results/{electionId}/party-seats")
    public List<Map<String, Object>> getPartySeatTally(@PathVariable Integer electionId) {
        return results.getPartySeatTally(electionId);
    }

    @GetMapping("/admin/stats/{electionId}")
    public Map<String, Object> getStats(@PathVariable Integer electionId) {
        return results.getElectionStats(electionId);
    }

    @GetMapping("/admin/results/{electionId}/machines")
    public List<Map<String, Object>> getVotesPerMachine(@PathVariable Integer electionId) {
        return results.getVotesPerMachine(electionId);
    }

    /** Turnout broken down by voter age band — see {@link ElectionResultsService#getTurnoutByAgeBand}. */
    @GetMapping("/admin/results/{electionId}/turnout-by-age")
    public List<Map<String, Object>> getTurnoutByAgeBand(@PathVariable Integer electionId) {
        return results.getTurnoutByAgeBand(electionId);
    }

    @GetMapping("/settings/language")
    public Map<String, Object> getLanguage() {
        Map<String, Object> response = new HashMap<>();
        response.put("language", results.getLanguage());
        return response;
    }

    @PostMapping("/admin/settings/language")
    public Map<String, Object> setLanguage(@RequestBody Map<String, String> body) {
        String language = body.getOrDefault("language", "EN");
        results.setLanguage(language);
        return Map.of("success", true, "language", language);
    }

    @GetMapping("/translations/{lang}")
    public List<Map<String, Object>> getTranslations(@PathVariable String lang) {
        return results.getTranslations(lang);
    }

    @GetMapping("/admin/states")
    public List<Map<String, Object>> getStates() {
        return results.getAllStates();
    }
}

package Backend.controller;

import Backend.service.ElectionResultsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@CrossOrigin("*")
public class ElectionResultsController {

    @Autowired private ElectionResultsService resultsService;

    @GetMapping("/admin/results/{electionId}")
    public Map<String,Object> getResults(@PathVariable Integer electionId) {
        return resultsService.getResults(electionId);
    }

    @GetMapping("/admin/results/{electionId}/state/{stateId}")
    public List<Map<String,Object>> getStateResults(@PathVariable Integer electionId, @PathVariable Integer stateId) {
        Map<String,Object> elec = resultsService.getResults(electionId);
        String type = elec.containsKey("election") ? (String)((Map)elec.get("election")).get("type") : "CM";
        if ("PM".equals(type)) return resultsService.getPmStateResults(electionId, stateId);
        return resultsService.getCmStateResults(electionId, stateId);
    }

    @GetMapping("/admin/results/{electionId}/states")
    public List<Map<String,Object>> getStatesWithVotes(@PathVariable Integer electionId) {
        return resultsService.getStatesWithVotes(electionId);
    }

    @GetMapping("/admin/stats/{electionId}")
    public Map<String,Object> getStats(@PathVariable Integer electionId) {
        return resultsService.getElectionStats(electionId);
    }

    @GetMapping("/settings/language")
    public Map<String,Object> getLanguage() {
        Map<String,Object> r = new HashMap<>();
        r.put("language", resultsService.getLanguage());
        return r;
    }

    @PostMapping("/admin/settings/language")
    public String setLanguage(@RequestBody Map<String,String> body) {
        String lang = body.getOrDefault("language","EN");
        resultsService.setLanguage(lang);
        return "Language set to " + lang;
    }

    @GetMapping("/translations/{lang}")
    public List<Map<String,Object>> getTranslations(@PathVariable String lang) {
        return resultsService.getTranslations(lang);
    }

    @GetMapping("/municipality/tiers")
    public List<Map<String,Object>> getTiers() {
        return resultsService.getMunicipalityTiers();
    }

    @GetMapping("/states")
    public List<Map<String,Object>> getStates() {
        return resultsService.getAllStates();
    }

    @GetMapping("/municipality/voter-status/{voterId}/{electionId}")
    public Map<String,Object> getMunicipalityStatus(@PathVariable String voterId, @PathVariable Integer electionId) {
        return resultsService.getMunicipalityVoterStatus(voterId, electionId);
    }
}
package Backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ElectionResultsService {

    @Autowired private JdbcTemplate jdbc;

    public Map<String,Object> getResults(Integer electionId) {
        Map<String,Object> result = new HashMap<>();
        List<Map<String,Object>> elecInfo = jdbc.queryForList(
                "SELECT id,name,name_ta,type,election_cycle FROM elections WHERE id=?",electionId);
        if (elecInfo.isEmpty()) return result;
        result.put("election",elecInfo.get(0));
        String type = (String) elecInfo.get(0).get("type");
        long totalVoters = jdbc.queryForObject("SELECT COUNT(*) FROM voters",Long.class);
        long totalVotes  = jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE election_id=?",Long.class,electionId);
        result.put("totalVoters",totalVoters);
        result.put("totalVotesCast",totalVotes);
        result.put("turnoutPercent", totalVoters>0 ? Math.round(totalVotes*1000.0/totalVoters)/10.0 : 0);
        if ("PM".equals(type))           result.put("breakdown",getPmResults(electionId));
        else if ("CM".equals(type))      result.put("breakdown",getCmResults(electionId));
        else if ("MUNICIPALITY".equals(type)) result.put("breakdown",getMunicipalityResults(electionId));
        return result;
    }

    // PM — results per state, per constituency
    public List<Map<String,Object>> getPmResults(Integer electionId) {
        return jdbc.queryForList(
                "SELECT s.name AS state_name, c.name AS constituency_name, v.candidate_name, COUNT(*) AS votes " +
                        "FROM vote v JOIN constituencies c ON c.id=v.constituency_id JOIN states s ON s.id=c.state_id " +
                        "WHERE v.election_id=? GROUP BY s.name,c.name,v.candidate_name ORDER BY s.name,c.name,votes DESC",electionId);
    }

    // PM — per state summary
    public List<Map<String,Object>> getPmStateResults(Integer electionId, Integer stateId) {
        return jdbc.queryForList(
                "SELECT c.name AS constituency_name, COALESCE(c.district_name,'') AS district, " +
                        "v.candidate_name, ca.party, COUNT(*) AS votes " +
                        "FROM vote v JOIN constituencies c ON c.id=v.constituency_id " +
                        "JOIN candidate ca ON ca.id=v.candidate_id " +
                        "WHERE v.election_id=? AND c.state_id=? " +
                        "GROUP BY c.name,c.district_name,v.candidate_name,ca.party ORDER BY c.district_name,c.name,votes DESC",
                electionId,stateId);
    }

    // CM — results per state, district-wise
    public List<Map<String,Object>> getCmResults(Integer electionId) {
        return jdbc.queryForList(
                "SELECT COALESCE(c.district_name,'') AS district_name, c.name AS constituency_name, " +
                        "v.candidate_name, COUNT(*) AS votes " +
                        "FROM vote v JOIN constituencies c ON c.id=v.constituency_id " +
                        "WHERE v.election_id=? GROUP BY c.district_name,c.name,v.candidate_name ORDER BY c.district_name,c.name,votes DESC",
                electionId);
    }

    // CM — filtered by state, district-wise
    public List<Map<String,Object>> getCmStateResults(Integer electionId, Integer stateId) {
        return jdbc.queryForList(
                "SELECT COALESCE(c.district_name,'Unknown') AS district_name, c.name AS constituency_name, " +
                        "v.candidate_name, ca.party, COUNT(*) AS votes " +
                        "FROM vote v JOIN constituencies c ON c.id=v.constituency_id " +
                        "JOIN candidate ca ON ca.id=v.candidate_id " +
                        "WHERE v.election_id=? AND c.state_id=? " +
                        "GROUP BY c.district_name,c.name,v.candidate_name,ca.party ORDER BY c.district_name,c.name,votes DESC",
                electionId,stateId);
    }

    // Municipality — per tier results (voter votes once per tier)
    public List<Map<String,Object>> getMunicipalityResults(Integer electionId) {
        return jdbc.queryForList(
                "SELECT mt.tier_number, mt.name_en AS tier_name, mt.name_ta AS tier_name_ta, mt.bg_color, " +
                        "v.candidate_name, COUNT(*) AS votes " +
                        "FROM vote v JOIN candidate ca ON ca.id=v.candidate_id JOIN municipality_tiers mt ON mt.id=ca.municipality_tier " +
                        "WHERE v.election_id=? GROUP BY mt.tier_number,mt.name_en,mt.name_ta,mt.bg_color,v.candidate_name ORDER BY mt.tier_number,votes DESC",
                electionId);
    }

    // States list (for CM state filter)
    public List<Map<String,Object>> getStatesWithVotes(Integer electionId) {
        return jdbc.queryForList(
                "SELECT DISTINCT s.id,s.name FROM vote v JOIN constituencies c ON c.id=v.constituency_id " +
                        "JOIN states s ON s.id=c.state_id WHERE v.election_id=? ORDER BY s.name",electionId);
    }

    public Map<String,Object> getElectionStats(Integer electionId) {
        Map<String,Object> stats = new HashMap<>();
        stats.put("totalVoters",  jdbc.queryForObject("SELECT COUNT(*) FROM voters",Long.class));
        stats.put("totalVotes",   jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE election_id=?",Long.class,electionId));
        stats.put("totalCandidates",jdbc.queryForObject("SELECT COUNT(*) FROM candidate WHERE election_id=?",Long.class,electionId));
        long tv=(long)stats.get("totalVoters"), vv=(long)stats.get("totalVotes");
        stats.put("turnout", tv>0 ? Math.round(vv*1000.0/tv)/10.0 : 0);
        return stats;
    }

    // Municipality tier status for a voter
    public Map<String,Object> getMunicipalityVoterStatus(String voterId, Integer electionId) {
        Map<String,Object> status = new HashMap<>();
        List<Map<String,Object>> tiers = jdbc.queryForList(
                "SELECT mt.id,mt.tier_number,mt.name_en, " +
                        "CASE WHEN v.voter_id IS NOT NULL THEN TRUE ELSE FALSE END AS voted " +
                        "FROM municipality_tiers mt " +
                        "LEFT JOIN vote v ON v.voter_id=? AND v.election_id=? AND v.municipality_tier=mt.id " +
                        "ORDER BY mt.tier_number",voterId,electionId);
        status.put("tiers",tiers);
        status.put("allVoted", tiers.stream().allMatch(t -> Boolean.TRUE.equals(t.get("voted"))));
        return status;
    }

    public String getLanguage() {
        try { return jdbc.queryForObject("SELECT value FROM election_settings WHERE key='language'",String.class); }
        catch(Exception e){ return "EN"; }
    }
    public void setLanguage(String lang) {
        jdbc.update("UPDATE election_settings SET value=? WHERE key='language'",lang);
    }
    public List<Map<String,Object>> getTranslations(String lang) {
        return jdbc.queryForList("SELECT key,value FROM translations WHERE lang=?",lang);
    }
    public List<Map<String,Object>> getMunicipalityTiers() {
        return jdbc.queryForList("SELECT * FROM municipality_tiers ORDER BY tier_number");
    }
    public List<Map<String,Object>> getAllStates() {
        return jdbc.queryForList("SELECT id,name,name_ta,name_hi,language_code,language_name,type,ls_seats,vs_seats FROM states ORDER BY name");
    }
}
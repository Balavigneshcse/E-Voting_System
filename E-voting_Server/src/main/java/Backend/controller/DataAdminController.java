package Backend.controller;

import Backend.model.State;
import Backend.repository.ConstituencyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/admin/data")
public class DataAdminController {

    @Autowired
    private JdbcTemplate jdbc;

    // ================================================================
    // FIXED ENDPOINTS - These were missing or broken
    // ================================================================

    /**
     * Get all states from the database (FIXED - was returning dummy data)
     */
    @GetMapping("/states")
    public List<Map<String, Object>> getStates() {
        try {
            String sql = "SELECT id, name, state_code FROM states ORDER BY name";
            return jdbc.queryForList(sql);
        } catch (Exception e) {
            // Return empty list if table doesn't exist yet
            return new ArrayList<>();
        }
    }

    /**
     * Get all elections
     */
    @GetMapping("/elections")
    public List<Map<String, Object>> getElections() {
        try {
            String sql = "SELECT id, name, type, status FROM elections ORDER BY name";
            return jdbc.queryForList(sql);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get constituencies by state and type (LS or VS)
     */
    @GetMapping("/constituencies")
    public List<Map<String, Object>> getConstituencies(
            @RequestParam(required = false) Integer stateId,
            @RequestParam(required = false) String type) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT c.id, c.name, c.type, c.state_id, " +
                            "COALESCE(d.name, '') as district_name, c.district_id " +
                            "FROM constituencies c " +
                            "LEFT JOIN districts d ON c.district_id = d.id " +
                            "WHERE 1=1"
            );
            List<Object> params = new ArrayList<>();

            if (stateId != null) {
                sql.append(" AND c.state_id = ?");
                params.add(stateId);
            }
            if (type != null && !type.isEmpty()) {
                sql.append(" AND c.type = ?");
                params.add(type);
            }
            sql.append(" ORDER BY c.name");

            return jdbc.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get districts by state
     */
    @GetMapping("/districts")
    public List<Map<String, Object>> getDistricts(@RequestParam Integer stateId) {
        try {
            String sql = "SELECT id, name, state_id FROM districts WHERE state_id = ? ORDER BY name";
            return jdbc.queryForList(sql, stateId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get VS constituencies by district
     */
    @GetMapping("/vs-by-district")
    public List<Map<String, Object>> getVsByDistrict(@RequestParam Integer districtId) {
        try {
            String sql = "SELECT id, name, type, district_id, " +
                    "COALESCE(category, 'GEN') as category " +
                    "FROM constituencies " +
                    "WHERE district_id = ? AND type = 'VS' " +
                    "ORDER BY name";
            return jdbc.queryForList(sql, districtId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get councils by VS constituency
     */
    @GetMapping("/councils-by-vs")
    public List<Map<String, Object>> getCouncilsByVs(@RequestParam Integer vsId) {
        try {
            String sql = "SELECT id, name, vs_constituency_id FROM councils " +
                    "WHERE vs_constituency_id = ? ORDER BY name";
            return jdbc.queryForList(sql, vsId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get councils (all)
     */
    @GetMapping("/councils")
    public List<Map<String, Object>> getCouncils() {
        try {
            String sql = "SELECT id, name, vs_constituency_id FROM councils ORDER BY name";
            return jdbc.queryForList(sql);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get panchayats by council
     */
    @GetMapping("/panchayats")
    public List<Map<String, Object>> getPanchayats(@RequestParam Integer councilId) {
        try {
            String sql = "SELECT id, name, council_id FROM panchayats " +
                    "WHERE council_id = ? ORDER BY name";
            return jdbc.queryForList(sql, councilId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Get wards by panchayat
     */
    @GetMapping("/wards")
    public List<Map<String, Object>> getWards(@RequestParam Integer panchayatId) {
        try {
            String sql = "SELECT id, name, ward_number, panchayat_id FROM wards " +
                    "WHERE panchayat_id = ? ORDER BY ward_number";
            return jdbc.queryForList(sql, panchayatId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Add a new voter
     */
    @PostMapping("/add-voter")
    public Map<String, Object> addVoter(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "INSERT INTO voters " +
                    "(voter_id, name, nfc_card_id, aadhaar_number, state_id, " +
                    "ls_constituency_id, vs_constituency_id, council_id, panchayat_id, ward_id, " +
                    "photo_base64, fingerprint_base64) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbc.update(sql,
                    body.get("voterId"),
                    body.get("name"),
                    body.get("nfcCardId"),
                    body.get("aadhaarNumber"),
                    body.get("stateId"),
                    body.get("lsConstituencyId"),
                    body.get("vsConstituencyId"),
                    body.get("councilId"),
                    body.get("panchayatId"),
                    body.get("wardId"),
                    body.get("photoBase64"),
                    body.get("fingerprintBase64")
            );

            response.put("success", true);
            response.put("message", "Voter registered successfully");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }
        return response;
    }

    /**
     * Add a new candidate
     */
    @PostMapping("/add-candidate")
    public Map<String, Object> addCandidate(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String sql = "INSERT INTO candidates " +
                    "(name, party, name_ta, party_ta, election_id, state_id, constituency_id, " +
                    "photo_url, symbol_url, party_color, photo_base64, symbol_base64) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            jdbc.update(sql,
                    body.get("name"),
                    body.get("party"),
                    body.get("nameTa"),
                    body.get("partyTa"),
                    body.get("electionId"),
                    body.get("stateId"),
                    body.get("constituencyId"),
                    body.get("photoUrl"),
                    body.get("symbolUrl"),
                    body.get("partyColor"),
                    body.get("photoBase64"),
                    body.get("symbolBase64")
            );

            response.put("success", true);
            response.put("message", "Candidate added successfully");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
        }
        return response;
    }

    // ================================================================
    // EXISTING GENERIC ENDPOINTS (preserved from original)
    // ================================================================

    @GetMapping("/list/{table}")
    public List<Map<String, Object>> listTable(@PathVariable String table) {
        String sql = "SELECT * FROM " + sanitize(table);
        return jdbc.queryForList(sql);
    }

    @GetMapping("/get/{table}/{id}")
    public Map<String, Object> getById(@PathVariable String table, @PathVariable String id) {
        String sql = "SELECT * FROM " + sanitize(table) + " WHERE id = ?";
        return jdbc.queryForMap(sql, toInt(id));
    }

    @PostMapping("/insert/{table}")
    public String insert(@PathVariable String table, @RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        int status = toInt(body.get("status"));
        String remarks = str(body.get("remarks"));
        String sql = "INSERT INTO " + sanitize(table) + " (name, status, remarks) VALUES (?, ?, ?)";
        int rows = jdbc.update(sql, name, status, remarks);
        return rows + " row(s) inserted.";
    }

    @PutMapping("/update/{table}/{id}")
    public String update(@PathVariable String table, @PathVariable String id,
                         @RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        int status = toInt(body.get("status"));
        String remarks = str(body.get("remarks"));
        String sql = "UPDATE " + sanitize(table) +
                " SET name = ?, status = ?, remarks = ? WHERE id = ?";
        int rows = jdbc.update(sql, name, status, remarks, toInt(id));
        return rows + " row(s) updated.";
    }

    @DeleteMapping("/delete/{table}/{id}")
    public String delete(@PathVariable String table, @PathVariable String id) {
        String sql = "DELETE FROM " + sanitize(table) + " WHERE id = ?";
        int rows = jdbc.update(sql, toInt(id));
        return rows + " row(s) deleted.";
    }

    @PostMapping("/query")
    public List<Map<String, Object>> runQuery(@RequestBody Map<String, String> body) {
        String sql = str(body.get("sql"));
        return jdbc.queryForList(sql);
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String sanitize(String identifier) {
        if (identifier == null || !identifier.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid identifier: " + identifier);
        }
        return identifier;
    }
}
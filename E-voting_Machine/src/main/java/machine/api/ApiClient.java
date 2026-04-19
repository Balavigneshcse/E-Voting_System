package machine.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * ApiClient — handles all HTTP communication with the E-Voting server.
 * Uses java.net.HttpClient (built into Java 11+, no Spring needed).
 */
public class ApiClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String machineSecret;
    private final String machineId;
    private       String machineToken = null;   // obtained after register

    public ApiClient(String baseUrl, String machineSecret, String machineId) {
        this.baseUrl       = baseUrl.replaceAll("/$", ""); // strip trailing slash
        this.machineSecret = machineSecret;
        this.machineId     = machineId;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.json = new ObjectMapper();
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 1 — MACHINE STARTUP
    // ═══════════════════════════════════════════════════════

    /** API 1 — Check if election is active */
    public Map<String,Object> getElectionStatus() throws Exception {
        return getRequest("/api/election/status", false);
    }

    /** API 2 — Get candidate list */
    public List<Map<String,Object>> getCandidates(Integer constituencyId) throws Exception {
        String url = "/api/candidates";
        if (constituencyId != null) url += "?constituencyId=" + constituencyId;
        return getRequestList("/api/candidates", false);
    }

    /** API 3 — Register this machine and get a token */
    public boolean registerMachine() throws Exception {
        Map<String,String> body = new LinkedHashMap<>();
        body.put("machineSecret", machineSecret);
        body.put("machineId",     machineId);
        Map<String,Object> res = postRequest("/api/machine/register", body, false);
        if (Boolean.TRUE.equals(res.get("success"))) {
            machineToken = (String) res.get("machineToken");
            System.out.println("✅ Machine registered. Token: " + machineToken);
            return true;
        }
        System.err.println("❌ Machine registration failed: " + res.get("message"));
        return false;
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 2 — VOTER AUTH
    // ═══════════════════════════════════════════════════════

    /** API 4 — Verify voter RFID/NFC card */
    public Map<String,Object> verifyCard(String rfidUidOrVoterId) throws Exception {
        return postRequest("/api/voter/verify-card",
            Map.of("rfidUid", rfidUidOrVoterId), true);
    }

    /** API 5 — Verify fingerprint */
    public Map<String,Object> verifyFingerprint(String voterId, String fingerprintHash) throws Exception {
        Map<String,String> body = new LinkedHashMap<>();
        body.put("voterId",          voterId);
        body.put("fingerprintHash",  fingerprintHash);
        body.put("simulateFingerprint", "true");
        return postRequest("/api/voter/verify-fingerprint", body, true);
    }

    /** API 6 — Get voter details (name, photo) */
    public Map<String,Object> getVoterDetails(String voterId) throws Exception {
        return getRequest("/api/voter/" + voterId + "/details", true);
    }

    /** API 7 — Check if voter has already voted */
    public Map<String,Object> getVoterStatus(String voterId) throws Exception {
        return getRequest("/api/voter/" + voterId + "/status", true);
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 3 — VOTING SESSION
    // ═══════════════════════════════════════════════════════

    /** API 8 — Start a voting session (returns sessionToken + candidates) */
    public Map<String,Object> startSession(String voterId) throws Exception {
        return postRequest("/api/session/start", Map.of("voterId", voterId), true);
    }

    /** API 9 — Cast a vote */
    public Map<String,Object> castVote(String sessionToken, int candidateId) throws Exception {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("sessionToken", sessionToken);
        body.put("candidateId",  candidateId);
        return postRequest("/api/vote/cast", body, true);
    }

    /** API 10 — Cancel session */
    public Map<String,Object> cancelSession(String sessionToken) throws Exception {
        return postRequest("/api/session/cancel", Map.of("sessionToken", sessionToken), true);
    }

    /** API 11 — Timeout session */
    public Map<String,Object> timeoutSession(String sessionToken) throws Exception {
        return postRequest("/api/session/timeout", Map.of("sessionToken", sessionToken), true);
    }

    // ═══════════════════════════════════════════════════════
    //  GROUP 4 & 5 — RESULTS & ADMIN
    // ═══════════════════════════════════════════════════════

    /** API 12 — Get results (requires adminKey) */
    public Map<String,Object> getResults(String adminKey) throws Exception {
        return getRequestWithAdminKey("/api/results", adminKey);
    }

    /** API 13 — Get audit log (requires adminKey) */
    public Map<String,Object> getAuditLog(String adminKey) throws Exception {
        return getRequestWithAdminKey("/api/audit/log", adminKey);
    }

    /** API 14 — Get turnout stats (requires adminKey) */
    public Map<String,Object> getTurnout(String adminKey) throws Exception {
        return getRequestWithAdminKey("/api/results/turnout", adminKey);
    }

    /** API 15 — Admin login */
    public Map<String,Object> adminLogin(String username, String password) throws Exception {
        Map<String,String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return postRequest("/api/admin/login", body, false);
    }

    /** API 16 — Open election (requires adminKey) */
    public Map<String,Object> openElection(int electionId, String adminKey) throws Exception {
        return postRequestWithAdminKey("/api/admin/election/open",
            Map.of("electionId", electionId), adminKey);
    }

    /** Close election (requires adminKey) */
    public Map<String,Object> closeElection(int electionId, String adminKey) throws Exception {
        return postRequestWithAdminKey("/api/admin/election/close",
            Map.of("electionId", electionId), adminKey);
    }

    // ═══════════════════════════════════════════════════════
    //  PRIVATE HTTP HELPERS
    // ═══════════════════════════════════════════════════════

    private Map<String,Object> getRequest(String path, boolean withMachineToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");

        if (withMachineToken && machineToken != null)
            req.header("X-Machine-Token", machineToken);

        HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return json.readValue(response.body(), new TypeReference<>() {});
    }

    private List<Map<String,Object>> getRequestList(String path, boolean withMachineToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");

        if (withMachineToken && machineToken != null)
            req.header("X-Machine-Token", machineToken);

        HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return json.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String,Object> getRequestWithAdminKey(String path, String adminKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Admin-Key", adminKey)
            .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        return json.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String,Object> postRequest(String path, Object body, boolean withMachineToken) throws Exception {
        String bodyStr = json.writeValueAsString(body);
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");

        if (withMachineToken && machineToken != null)
            req.header("X-Machine-Token", machineToken);

        HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return json.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String,Object> postRequestWithAdminKey(String path, Object body, String adminKey) throws Exception {
        String bodyStr = json.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Admin-Key", adminKey)
            .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        return json.readValue(response.body(), new TypeReference<>() {});
    }

    public String getMachineToken() { return machineToken; }
    public String getBaseUrl()      { return baseUrl; }
}

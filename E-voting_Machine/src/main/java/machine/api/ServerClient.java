package machine.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import machine.config.MachineSettings;
import machine.crypto.MachineCrypto;
import machine.queue.PendingVote;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every call the terminal makes to the server, over an authenticated encrypted channel.
 *
 * <p>Four layers protect a request, mirroring the server's filter:
 * <ol>
 *   <li><b>TLS</b> — the connection is HTTPS, and the server certificate is checked against
 *       a configured truststore.</li>
 *   <li><b>Token</b> — a machine JWT obtained at registration, sent as a bearer token.</li>
 *   <li><b>Signature</b> — HMAC-SHA256 over the method, path, timestamp, nonce and a hash
 *       of the body, keyed with a secret unique to this terminal. A stolen token alone
 *       cannot cast a vote.</li>
 *   <li><b>Freshness</b> — a timestamp and a single-use nonce, so a captured request cannot
 *       be replayed.</li>
 * </ol>
 *
 * <p>On top of that, the ballot choice itself is sealed with AES-256-GCM before it is put
 * in the request body, so the candidate a voter picked is not visible to anything between
 * the TLS termination point and the server's vote handler.
 */
public class ServerClient {

    /** Must match the server's HKDF context labels exactly. */
    private static final String SIGNING_KEY_INFO = "evoting/machine-request-signature/v1";
    private static final String PAYLOAD_KEY_INFO = "evoting/vote-payload/v1";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final MachineSettings settings;
    private final HttpClient      http;
    private final ObjectMapper    json;
    private final String          baseUrl;

    private volatile String machineToken;
    private volatile byte[] signatureKey;
    private volatile byte[] payloadKey;

    public ServerClient(MachineSettings settings) {
        this.settings = settings;
        this.baseUrl  = settings.serverUrl();
        this.json     = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http     = buildHttpClient(settings);
    }

    public boolean isRegistered() {
        return machineToken != null && signatureKey != null;
    }

    // ── Registration ────────────────────────────────────────────────────────

    /**
     * Exchanges the one-time provisioning secret for a token and a signing key.
     *
     * <p>The only unsigned call, because there is no signing key until it returns one. Its
     * confidentiality therefore rests entirely on TLS, which is why the terminal refuses to
     * run over plain HTTP unless explicitly overridden.
     */
    public ApiResponses.Registration register() throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("machineId", settings.machineId());
        body.put("provisioningSecret", settings.provisioningSecret());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/machine/register"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeJson(body)))
                .build();

        ApiResponses.Registration registration =
                send(request, ApiResponses.Registration.class);

        if (registration.success()) {
            adoptCredentials(registration);
        }
        return registration;
    }

    private void adoptCredentials(ApiResponses.Registration registration) {
        byte[] rootKey = MachineCrypto.fromBase64(registration.signingKeyBase64());
        byte[] salt    = MachineCrypto.utf8(settings.machineId());

        this.machineToken = registration.machineToken();
        this.signatureKey = MachineCrypto.hkdf(rootKey, salt, SIGNING_KEY_INFO, MachineCrypto.AES_KEY_BYTES);
        this.payloadKey   = MachineCrypto.hkdf(rootKey, salt, PAYLOAD_KEY_INFO, MachineCrypto.AES_KEY_BYTES);
    }

    // ── Machine API ─────────────────────────────────────────────────────────

    public ApiResponses.ElectionStatus electionStatus() throws IOException {
        return signedGet("/api/election/status", ApiResponses.ElectionStatus.class);
    }

    public ApiResponses.CardResult verifyCard(String cardIdentifier) throws IOException {
        return signedPost("/api/voter/verify-card",
                Map.of("rfidUid", cardIdentifier), ApiResponses.CardResult.class);
    }

    public ApiResponses.FingerprintResult verifyFingerprint(String voterId, String sample)
            throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("voterId", voterId);
        body.put("fingerprintSample", sample);
        return signedPost("/api/voter/verify-fingerprint", body, ApiResponses.FingerprintResult.class);
    }

    public ApiResponses.VoterDetails voterDetails(String voterId) throws IOException {
        return signedGet("/api/voter/" + voterId + "/details", ApiResponses.VoterDetails.class);
    }

    public ApiResponses.SessionResult startSession(String voterId, String biometricToken)
            throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("voterId", voterId);
        body.put("biometricToken", biometricToken);
        return signedPost("/api/session/start", body, ApiResponses.SessionResult.class);
    }

    /**
     * Delivers a vote, sealing the ballot choice before it leaves the terminal.
     *
     * <p>Safe to call repeatedly with the same {@link PendingVote}: the idempotency key
     * inside the sealed payload means the server counts the first delivery and answers
     * later ones with the same receipt. That is what lets the queue retry without ever
     * risking a double count.
     */
    public ApiResponses.VoteReceipt castVote(PendingVote vote) throws IOException {
        Map<String, Object> plaintext = new LinkedHashMap<>();
        plaintext.put("sessionToken",   vote.sessionToken());
        plaintext.put("candidateId",    vote.candidateId());
        plaintext.put("idempotencyKey", vote.idempotencyKey());
        plaintext.put("castAt",         vote.castAtEpochMillis());

        String envelope = MachineCrypto.encryptToEnvelope(requirePayloadKey(), writeJson(plaintext));
        return signedPost("/api/vote/cast", Map.of("payload", envelope), ApiResponses.VoteReceipt.class);
    }

    public ApiResponses.SimpleResult cancelSession(String sessionToken) throws IOException {
        return signedPost("/api/session/cancel",
                Map.of("sessionToken", sessionToken), ApiResponses.SimpleResult.class);
    }

    public ApiResponses.SimpleResult timeoutSession(String sessionToken) throws IOException {
        return signedPost("/api/session/timeout",
                Map.of("sessionToken", sessionToken), ApiResponses.SimpleResult.class);
    }

    /**
     * A candidate's photo, or {@code null} if none is on file — checked via
     * {@link ApiResponses.CandidateOption#hasPhoto()} before this is ever called, so a
     * null here means the server disagreed, not that the caller should treat it as an error.
     */
    public byte[] candidatePhoto(int candidateId) throws IOException {
        return fetchImageBytes("/api/candidate/" + candidateId + "/photo");
    }

    /** A candidate's party symbol, the way it appears beside their name on a physical
     *  EVM ballot, or {@code null} if none is on file. */
    public byte[] candidateSymbol(int candidateId) throws IOException {
        return fetchImageBytes("/api/candidate/" + candidateId + "/symbol");
    }

    private byte[] fetchImageBytes(String path) throws IOException {
        HttpRequest request = signedRequestBuilder("GET", path, null).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() >= 500) {
                throw new ServerUnavailableException("Server is busy (HTTP " + response.statusCode() + ").");
            }
            if (response.statusCode() == 401) {
                machineToken = null;
                throw new IOException("Not authenticated (HTTP 401).");
            }
            if (response.statusCode() >= 400) {
                throw new IOException("Could not fetch image (HTTP " + response.statusCode() + ").");
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new ServerUnavailableException("Server did not respond in time.", e);
        } catch (java.net.ConnectException | java.net.UnknownHostException
                 | javax.net.ssl.SSLHandshakeException e) {
            throw new ServerUnavailableException("Cannot reach the server: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerUnavailableException("Request interrupted.", e);
        }
    }

    // ── Election officer actions ─────────────────────────────────────────────

    /** Elections the officer may open or close, for the terminal's officer panel. */
    public Map<String, Object> adminElections() throws IOException {
        HttpRequest request = signedRequestBuilder("GET", "/api/admin/elections", null)
                .header(ADMIN_KEY_HEADER, requireAdminKey())
                .GET()
                .build();
        return sendForMap(request);
    }

    public ApiResponses.SimpleResult openElection(int electionId) throws IOException {
        return adminPost("/api/admin/election/open",
                Map.of("electionId", electionId), ApiResponses.SimpleResult.class);
    }

    public ApiResponses.SimpleResult closeElection(int electionId) throws IOException {
        return adminPost("/api/admin/election/close",
                Map.of("electionId", electionId), ApiResponses.SimpleResult.class);
    }

    public Map<String, Object> turnout() throws IOException {
        HttpRequest request = signedRequestBuilder("GET", "/api/results/turnout", null)
                .header(ADMIN_KEY_HEADER, requireAdminKey())
                .GET()
                .build();
        return sendForMap(request);
    }

    // ── Signed request plumbing ─────────────────────────────────────────────

    private static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private <T> T signedGet(String path, Class<T> type) throws IOException {
        HttpRequest request = signedRequestBuilder("GET", path, null).GET().build();
        return send(request, type);
    }

    private <T> T signedPost(String path, Object body, Class<T> type) throws IOException {
        byte[] payload = writeJson(body);
        HttpRequest request = signedRequestBuilder("POST", path, payload)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return send(request, type);
    }

    private <T> T adminPost(String path, Object body, Class<T> type) throws IOException {
        byte[] payload = writeJson(body);
        HttpRequest request = signedRequestBuilder("POST", path, payload)
                .header(ADMIN_KEY_HEADER, requireAdminKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return send(request, type);
    }

    /**
     * Builds a request carrying the bearer token and the HMAC signature.
     *
     * <p>The signature covers the path without its query string, because that is what the
     * server hashes. Signing the full URL instead would fail on every request that has
     * query parameters.
     */
    private HttpRequest.Builder signedRequestBuilder(String method, String pathWithQuery, byte[] body) {
        String token = requireToken();

        int    queryStart = pathWithQuery.indexOf('?');
        String signedPath = queryStart < 0 ? pathWithQuery : pathWithQuery.substring(0, queryStart);

        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce     = MachineCrypto.randomNonce();
        String canonical = String.join("\n",
                method.toUpperCase(),
                signedPath,
                timestamp,
                nonce,
                MachineCrypto.sha256Hex(body == null ? new byte[0] : body));

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathWithQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Machine-Id", settings.machineId())
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", MachineCrypto.hmacSha256Hex(signatureKey, canonical));
    }

    private <T> T send(HttpRequest request, Class<T> type) throws IOException {
        HttpResponse<String> response = exchange(request);
        try {
            return json.readValue(response.body(), type);
        } catch (IOException e) {
            throw new IOException("Server replied with something unreadable (HTTP "
                    + response.statusCode() + "): " + abbreviate(response.body()), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendForMap(HttpRequest request) throws IOException {
        HttpResponse<String> response = exchange(request);
        return json.readValue(response.body(), Map.class);
    }

    private HttpResponse<String> exchange(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                // The server is up but struggling. Treated as unavailable so a vote is
                // queued and retried rather than reported to the voter as a refusal.
                throw new ServerUnavailableException(
                        "Server is busy (HTTP " + response.statusCode() + ").");
            }
            if (response.statusCode() == 401) {
                // Credentials went stale; drop them so the next boot re-registers.
                machineToken = null;
            }
            return response;
        } catch (HttpTimeoutException e) {
            throw new ServerUnavailableException("Server did not respond in time.", e);
        } catch (java.net.ConnectException | java.net.UnknownHostException
                 | javax.net.ssl.SSLHandshakeException e) {
            throw new ServerUnavailableException("Cannot reach the server: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ServerUnavailableException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerUnavailableException("Request interrupted.", e);
        }
    }

    private byte[] writeJson(Object value) throws IOException {
        return json.writeValueAsBytes(value);
    }

    private String requireToken() {
        String token = machineToken;
        if (token == null) {
            throw new IllegalStateException("This terminal is not registered with the server.");
        }
        return token;
    }

    private byte[] requirePayloadKey() {
        byte[] key = payloadKey;
        if (key == null) {
            throw new IllegalStateException("No payload key. Register the terminal first.");
        }
        return key;
    }

    private String requireAdminKey() {
        String key = settings.adminKey();
        if (key == null) {
            throw new IllegalStateException(
                    "ADMIN_KEY is not configured on this terminal, so officer actions are unavailable.");
        }
        return key;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }

    // ── TLS ─────────────────────────────────────────────────────────────────

    private static HttpClient buildHttpClient(MachineSettings settings) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);

        if (settings.allowInsecureTransport()) {
            System.err.println("""
                    WARNING: ALLOW_INSECURE_TRANSPORT is enabled. Ballots may travel unencrypted.
                    Use this only on an isolated bench, never in a polling booth.""");
        }

        try {
            if (settings.trustAnyCertificate()) {
                System.err.println("""
                        WARNING: TRUST_ANY_CERTIFICATE is enabled. The terminal will accept any
                        server certificate, so it cannot tell the real server from an impostor.
                        Import the server certificate into a truststore before real use.""");
                builder.sslContext(trustAnythingContext());
            } else {
                String truststorePath = settings.truststorePath();
                if (truststorePath != null) {
                    builder.sslContext(truststoreContext(
                            Paths.get(truststorePath), settings.truststorePassword()));
                }
                // Otherwise the JDK's default trust store applies, which is correct for a
                // certificate issued by a recognised authority.
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not configure TLS: " + e.getMessage(), e);
        }
        return builder.build();
    }

    private static SSLContext truststoreContext(Path path, String password) throws Exception {
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Truststore not readable: " + path.toAbsolutePath());
        }
        String type = path.toString().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
        KeyStore truststore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            truststore.load(in, password == null ? new char[0] : password.toCharArray());
        }
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(truststore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static SSLContext trustAnythingContext() throws Exception {
        TrustManager[] acceptAll = { new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, acceptAll, MachineCrypto.secureRandom());
        return context;
    }
}

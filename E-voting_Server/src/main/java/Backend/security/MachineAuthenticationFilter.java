package Backend.security;

import Backend.model.Machine;
import Backend.repository.MachineRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates every voting-terminal request before it reaches a controller.
 *
 * <p>Four independent checks, each closing a different hole:
 * <ol>
 *   <li><b>TLS</b> — the request must have arrived over HTTPS. Vote traffic
 *       previously travelled as plain HTTP.</li>
 *   <li><b>Token</b> — a revocable machine JWT identifies the terminal. Previously a
 *       single shared secret produced random tokens held in a static in-memory set
 *       that survived no restart and could never be withdrawn.</li>
 *   <li><b>Signature</b> — HMAC-SHA256 over method, path, timestamp, nonce and a
 *       hash of the body, using a key unique to that terminal. A stolen token alone
 *       is therefore not enough to cast a vote.</li>
 *   <li><b>Freshness and replay</b> — the timestamp must be recent and the nonce
 *       unused, so a captured request cannot be resent.</li>
 * </ol>
 *
 * <p>{@code POST /api/machine/register} is deliberately exempt: a terminal has no
 * signing key until registration hands it one. That endpoint authenticates instead
 * with its one-time provisioning secret, over TLS.
 */
@Component
@Order(1)
public class MachineAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_MACHINE_ID = "X-Machine-Id";
    public static final String HEADER_TIMESTAMP  = "X-Timestamp";
    public static final String HEADER_NONCE      = "X-Nonce";
    public static final String HEADER_SIGNATURE  = "X-Signature";

    private static final String MACHINE_API_PREFIX  = "/api/";
    private static final String REGISTRATION_PATH   = "/api/machine/register";
    private static final String BEARER_PREFIX       = "Bearer ";

    /** How often a terminal's last-seen timestamp is written back. */
    private static final Duration LAST_SEEN_WRITE_INTERVAL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(MachineAuthenticationFilter.class);

    private final MachineRepository        machines;
    private final MachineJwtService        jwtService;
    private final MachineCredentialService credentials;
    private final ReplayGuard              replayGuard;
    private final MachineSecurityProperties properties;

    /** Throttles last-seen writes so a busy terminal does not cause an UPDATE per request. */
    private final Map<String, LocalDateTime> lastSeenWrites = new ConcurrentHashMap<>();

    public MachineAuthenticationFilter(MachineRepository machines,
                                       MachineJwtService jwtService,
                                       MachineCredentialService credentials,
                                       ReplayGuard replayGuard,
                                       MachineSecurityProperties properties) {
        this.machines    = machines;
        this.jwtService  = jwtService;
        this.credentials = credentials;
        this.replayGuard = replayGuard;
        this.properties  = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(MACHINE_API_PREFIX) || REGISTRATION_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (properties.isRequireTls() && !isSecure(request)) {
            reject(response, "TLS required. Voting terminals must connect over HTTPS.");
            return;
        }

        Optional<String> tokenMachineId = jwtService.verify(bearerToken(request));
        if (tokenMachineId.isEmpty()) {
            reject(response, "Invalid or expired machine token. Re-register this terminal.");
            return;
        }
        String machineId = tokenMachineId.get();

        String claimedId = request.getHeader(HEADER_MACHINE_ID);
        if (claimedId != null && !claimedId.equals(machineId)) {
            reject(response, "Machine identity header does not match the presented token.");
            return;
        }

        Machine machine = machines.findById(machineId).orElse(null);
        if (machine == null || !machine.isActive()) {
            reject(response, "This terminal is not active. Contact the election officer.");
            return;
        }

        // Body is buffered here because signature verification hashes it and the
        // controller still needs to deserialise it afterwards.
        CachedBodyHttpServletRequest buffered = new CachedBodyHttpServletRequest(request);

        if (properties.isRequireSignature() && !hasValidSignature(buffered, machine)) {
            reject(response, "Request signature missing, stale, replayed or incorrect.");
            return;
        }

        MachineRequestContext.set(buffered, machine);
        recordLastSeen(machine);
        chain.doFilter(buffered, response);
    }

    private boolean hasValidSignature(CachedBodyHttpServletRequest request, Machine machine) {
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce     = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);

        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            log.debug("Signature headers missing on {} {}", request.getMethod(), request.getRequestURI());
            return false;
        }
        if (!isFresh(timestamp)) {
            return false;
        }
        if (!replayGuard.claim(nonce, machine.getMachineId())) {
            return false;
        }

        String canonical = canonicalRequest(
                request.getMethod(), request.getRequestURI(), timestamp, nonce, request.body());
        String expected = CryptoSupport.hmacSha256Hex(
                credentials.requestSignatureKey(machine), canonical);

        if (!CryptoSupport.constantTimeEquals(expected, signature.trim().toLowerCase())) {
            log.warn("Signature mismatch from terminal {} on {} {}",
                    machine.getMachineId(), request.getMethod(), request.getRequestURI());
            return false;
        }
        return true;
    }

    /**
     * The exact string both sides sign. Any divergence between server and terminal
     * here produces a signature mismatch, so it is defined in one place and mirrored
     * verbatim by the machine client.
     */
    public static String canonicalRequest(String method, String path,
                                          String timestamp, String nonce, byte[] body) {
        return String.join("\n",
                method.toUpperCase(),
                path,
                timestamp,
                nonce,
                CryptoSupport.sha256Hex(body == null ? new byte[0] : body));
    }

    private boolean isFresh(String timestampHeader) {
        long sent;
        try {
            sent = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            log.debug("Unparseable request timestamp: {}", timestampHeader);
            return false;
        }
        long skewMillis = Math.abs(System.currentTimeMillis() - sent);
        boolean fresh = skewMillis <= properties.getSignatureTolerance().toMillis();
        if (!fresh) {
            log.warn("Rejected request outside freshness window: clock skew {} ms.", skewMillis);
        }
        return fresh;
    }

    private void recordLastSeen(Machine machine) {
        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime last = lastSeenWrites.get(machine.getMachineId());
        if (last != null && last.plus(LAST_SEEN_WRITE_INTERVAL).isAfter(now)) {
            return;
        }
        lastSeenWrites.put(machine.getMachineId(), now);
        try {
            machine.touch();
            machines.save(machine);
        } catch (RuntimeException e) {
            // Presence tracking is diagnostic. It must never fail a vote.
            log.debug("Could not update last-seen for {}: {}", machine.getMachineId(), e.getMessage());
        }
    }

    private static boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}

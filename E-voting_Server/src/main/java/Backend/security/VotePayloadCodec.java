package Backend.security;

import Backend.model.Machine;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Decrypts the vote payload that a terminal sends inside its request body.
 *
 * <p>TLS already protects the request in transit, so why encrypt again? Because TLS
 * terminates at the server's edge. Anything that sits between that edge and the
 * application — a reverse proxy, a load balancer, request logging, an APM agent — sees
 * decrypted bodies. A ballot choice is the one field in this system that should never
 * appear in a proxy log, so it is sealed end-to-end with a key only the terminal and
 * the vote handler possess.
 *
 * <p>AES-256-GCM is authenticated, so a modified ciphertext fails to decrypt rather
 * than silently producing a different candidate ID.
 */
@Component
public class VotePayloadCodec {

    private final MachineCredentialService credentials;
    private final ObjectMapper json;

    public VotePayloadCodec(MachineCredentialService credentials, ObjectMapper json) {
        this.credentials = credentials;
        this.json        = json;
    }

    /**
     * @throws InvalidPayloadException when the envelope is malformed, was tampered
     *         with, or was encrypted for a different terminal
     */
    public SealedVote open(Machine machine, String envelope) {
        if (envelope == null || envelope.isBlank()) {
            throw new InvalidPayloadException("Encrypted vote payload is missing.");
        }
        byte[] plaintext;
        try {
            plaintext = CryptoSupport.decryptFromEnvelope(credentials.payloadKey(machine), envelope);
        } catch (RuntimeException e) {
            throw new InvalidPayloadException(
                    "Vote payload failed authenticated decryption. It was altered in transit "
                            + "or encrypted with the wrong terminal key.");
        }
        try {
            SealedVote vote = json.readValue(new String(plaintext, StandardCharsets.UTF_8), SealedVote.class);
            if (vote.sessionToken() == null || vote.sessionToken().isBlank()) {
                throw new InvalidPayloadException("Vote payload has no session token.");
            }
            if (vote.candidateId() == null) {
                throw new InvalidPayloadException("Vote payload has no candidate.");
            }
            if (vote.idempotencyKey() == null || vote.idempotencyKey().isBlank()) {
                throw new InvalidPayloadException(
                        "Vote payload has no idempotency key, so a queued retry could not be "
                                + "distinguished from a second vote.");
            }
            return vote;
        } catch (InvalidPayloadException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidPayloadException("Vote payload is not valid JSON.");
        }
    }

    /** Encrypts a payload for a terminal. Used by tests and diagnostics. */
    public String seal(Machine machine, SealedVote vote) {
        try {
            byte[] plaintext = json.writeValueAsBytes(vote);
            return CryptoSupport.encryptToEnvelope(credentials.payloadKey(machine), plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("Could not seal vote payload", e);
        }
    }

    /**
     * The decrypted ballot instruction.
     *
     * @param sessionToken   the voting session this vote belongs to
     * @param candidateId    chosen candidate
     * @param idempotencyKey terminal-generated key that makes a queued retry safe to
     *                       resend; the server counts the first arrival only
     * @param castAt         epoch milliseconds at which the voter actually pressed
     *                       Confirm. This is what the session window is checked against,
     *                       not the arrival time — otherwise a vote the terminal held
     *                       during a network outage would fail on an expired session and
     *                       be lost. Sealed inside the encrypted payload and covered by
     *                       the request signature, so it cannot be altered in transit.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SealedVote(String sessionToken, Integer candidateId,
                             String idempotencyKey, Long castAt) {

        /** Falls back to arrival time for a payload from an older terminal build. */
        public long effectiveCastAt() {
            return castAt == null ? System.currentTimeMillis() : castAt;
        }
    }

    /** Signals a payload that cannot be trusted. Mapped to HTTP 400 by the controller. */
    public static class InvalidPayloadException extends RuntimeException {
        public InvalidPayloadException(String message) {
            super(message);
        }
    }
}

package machine.queue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * A vote the voter has confirmed but the server has not yet acknowledged.
 *
 * @param idempotencyKey    generated once, here, and reused for every delivery attempt.
 *                          This is what makes retrying safe: the server records the first
 *                          arrival and answers duplicates with the original receipt, so a
 *                          vote can never be counted twice no matter how many times the
 *                          network fails mid-request.
 * @param castAtEpochMillis when the voter pressed Confirm. The server validates the
 *                          session window against this rather than against arrival time,
 *                          so a vote held through an outage is still accepted.
 * @param attempts          delivery attempts so far, for backoff and for the display.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PendingVote(
        String  idempotencyKey,
        String  sessionToken,
        int     candidateId,
        String  candidateName,
        String  electionName,
        long    castAtEpochMillis,
        int     attempts) {

    public static PendingVote create(String sessionToken, int candidateId,
                                     String candidateName, String electionName) {
        return new PendingVote(
                UUID.randomUUID().toString(),
                sessionToken,
                candidateId,
                candidateName,
                electionName,
                System.currentTimeMillis(),
                0);
    }

    public PendingVote withAnotherAttempt() {
        return new PendingVote(idempotencyKey, sessionToken, candidateId, candidateName,
                electionName, castAtEpochMillis, attempts + 1);
    }

    public Instant castAt() {
        return Instant.ofEpochMilli(castAtEpochMillis);
    }

    public long ageSeconds() {
        return (System.currentTimeMillis() - castAtEpochMillis) / 1000;
    }
}

package Backend.dto;

import java.util.List;

/** A opened voting session together with the ballot the voter is entitled to. */
public record SessionResult(
        boolean               success,
        String                message,
        String                sessionToken,
        String                voterName,
        String                electionName,
        String                electionType,
        Integer               constituencyId,
        String                constituencyName,
        long                  expiresInSeconds,
        List<CandidateOption> candidates) {

    public static SessionResult fail(String message) {
        return new SessionResult(false, message, null, null, null, null, null, null, 0, List.of());
    }
}

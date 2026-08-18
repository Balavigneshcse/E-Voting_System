package machine.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Typed views of the server's replies.
 *
 * <p>The previous client passed {@code Map<String,Object>} around and reached into it with
 * string keys and casts at each use site, so a renamed field failed at runtime in the UI
 * rather than at compile time. Unknown fields are ignored so the server can add response
 * fields without breaking a deployed terminal.
 */
public final class ApiResponses {

    private ApiResponses() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Registration(
            boolean success,
            String  message,
            String  machineToken,
            long    expiresInSeconds,
            String  signingKeyBase64,
            String  machineId,
            String  label,
            String  boothName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ElectionStatus(
            boolean isActive,
            Integer electionId,
            String  electionName,
            String  electionNameTa,
            String  electionType,
            Integer electionCycle,
            String  machineId,
            String  message) {}

    /**
     * @param simulatedFingerprintCode the stand-in for a scanned fingerprint, supplied by
     *        the server while hardware simulation is on. The terminal treats it as data
     *        read from the card, exactly as it treats the card UID.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardResult(
            boolean success,
            String  message,
            String  voterId,
            String  voterName,
            boolean hasVoted,
            String  simulatedFingerprintCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FingerprintResult(
            boolean success,
            boolean match,
            String  message,
            String  voterId,
            String  biometricToken,
            long    expiresInSeconds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VoterDetails(
            boolean success,
            String  message,
            String  voterId,
            String  name,
            Integer constituencyId,
            String  constituencyName,
            Integer lsConstituencyId,
            String  lsConstituencyName,
            Boolean cardActive,
            Boolean fingerprintEnrolled,
            String  photoBase64,
            String  photoType) {}

    /**
     * @param hasPhoto whether {@code GET /api/candidate/{id}/photo} has an image.
     * @param hasSymbol whether {@code GET /api/candidate/{id}/symbol} has an image — the
     *        party symbol shown beside the candidate's name, as on a physical EVM ballot.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CandidateOption(
            Integer id,
            String  name,
            String  nameTa,
            String  party,
            String  partyTa,
            int     slotNumber,
            boolean hasPhoto,
            boolean hasSymbol) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
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
            List<CandidateOption> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VoteReceipt(
            boolean success,
            String  message,
            String  receipt,
            Long    blockNumber,
            String  blockHash,
            String  electionName,
            boolean duplicate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimpleResult(boolean success, String message) {}
}

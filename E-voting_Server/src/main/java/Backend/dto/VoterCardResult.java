package Backend.dto;

/**
 * Outcome of a voter card read.
 *
 * @param simulatedFingerprintCode stand-in for what the fingerprint scanner would
 *        capture, returned only while hardware simulation is enabled. A real card read
 *        leaves this null and the physical scanner supplies the sample instead.
 */
public record VoterCardResult(
        boolean success,
        String  message,
        String  voterId,
        String  voterName,
        boolean hasVoted,
        String  simulatedFingerprintCode) {

    public static VoterCardResult fail(String message) {
        return new VoterCardResult(false, message, null, null, false, null);
    }
}

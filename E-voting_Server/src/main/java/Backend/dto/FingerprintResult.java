package Backend.dto;

/**
 * Outcome of a fingerprint check.
 *
 * @param match          whether the submitted sample matched the enrolled template
 * @param biometricToken single-use proof of the match, required to open a voting
 *                       session. Null unless {@code match} is true.
 */
public record FingerprintResult(
        boolean success,
        boolean match,
        String  message,
        String  voterId,
        String  biometricToken,
        long    expiresInSeconds) {

    public static FingerprintResult fail(String message) {
        return new FingerprintResult(false, false, message, null, null, 0);
    }

    public static FingerprintResult mismatch(String voterId) {
        return new FingerprintResult(true, false,
                "Fingerprint did not match. Please place your finger again.", voterId, null, 0);
    }
}

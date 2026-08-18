package machine.hardware;

/**
 * The fingerprint scanner.
 *
 * <p>Exists so the simulation and the real Mantra MFS100 named in the quotation are
 * interchangeable. Either way the terminal's job is the same: capture a sample and send it
 * to the server for comparison.
 *
 * <p>The terminal never decides whether a fingerprint matched. It cannot: the enrolled
 * template lives on the server and never leaves it. This is deliberate — the previous
 * client effectively asserted its own success, which made the biometric step decorative.
 */
public interface FingerprintScanner {

    String description();

    boolean isSimulated();

    /**
     * Captures a fingerprint and returns the sample the server will verify.
     *
     * <p>With the MFS100 this becomes the ISO 19794-2 template from the scanner SDK. In
     * simulation it is the sample code carried on the voter's card.
     *
     * @throws HardwareException if no finger was detected or the capture failed
     */
    String captureSample() throws HardwareException;
}

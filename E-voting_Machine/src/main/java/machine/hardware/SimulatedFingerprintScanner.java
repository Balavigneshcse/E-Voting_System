package machine.hardware;

/**
 * Stands in for the Mantra MFS100 until it is connected.
 *
 * <p>The sample it returns is the code the server handed over during the card read, so it
 * is treated as data carried on the voter's card rather than as something the terminal made
 * up. The server then hashes it and compares against the enrolled template in the ordinary
 * way, which keeps the verification path genuine: a wrong or missing code fails.
 *
 * <p>Being honest about the limit: because the terminal is given a value that will match,
 * this simulates the <em>plumbing</em> of biometric verification, not its security. It
 * cannot demonstrate that the right person is present. Only the real scanner can do that,
 * and swapping it in means implementing this one method against the MFS100 SDK.
 */
public class SimulatedFingerprintScanner implements FingerprintScanner {

    private volatile String loadedSample;
    private volatile boolean fingerPresent;

    /** Called after a card read, with the sample code the card is standing in for. */
    public void loadSampleFromCard(String sampleCode) {
        this.loadedSample  = sampleCode;
        this.fingerPresent = false;
    }

    /** Called when the voter presses the fingerprint pad. */
    public void placeFinger() {
        this.fingerPresent = true;
    }

    public void clear() {
        this.loadedSample  = null;
        this.fingerPresent = false;
    }

    /** Whether a simulated capture can succeed at all, so the UI can explain if not. */
    public boolean hasSample() {
        return loadedSample != null && !loadedSample.isEmpty();
    }

    @Override
    public String description() {
        return "Simulated fingerprint scanner (MFS100 not connected)";
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public String captureSample() throws HardwareException {
        if (!fingerPresent) {
            throw new HardwareException("No finger detected. Place your finger on the scanner.");
        }
        if (!hasSample()) {
            throw new HardwareException(
                    "This card carries no fingerprint data. Ask the booth officer to re-issue it.");
        }
        return loadedSample;
    }
}

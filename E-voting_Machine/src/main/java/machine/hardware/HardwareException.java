package machine.hardware;

/** A peripheral could not complete a read. Recoverable: the voter simply tries again. */
public class HardwareException extends Exception {

    public HardwareException(String message) {
        super(message);
    }
}

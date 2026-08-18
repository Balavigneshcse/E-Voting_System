package machine.api;

import java.io.IOException;

/**
 * The server could not be reached, or did not answer in time.
 *
 * <p>Distinguished from every other failure on purpose. A refused vote is final and the
 * voter must be told; an unreachable server is temporary, so the vote is queued and
 * retried. Collapsing the two is what caused the previous client to discard a vote with a
 * generic "Network error" dialog.
 */
public class ServerUnavailableException extends IOException {

    public ServerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ServerUnavailableException(String message) {
        super(message);
    }
}

package machine.hardware;

/**
 * Stands in for the RC522 reader until it is wired up.
 *
 * <p>A real tap and a simulated tap both amount to one thing: an identifier arriving from
 * the card. Here the operator supplies it, and the server still has to recognise it against
 * the voter roll, so an invented identifier is rejected exactly as an unregistered card
 * would be.
 */
public class SimulatedCardReader implements CardReader {

    private volatile String presentedIdentifier;

    /** Called by the UI when the operator enters a card identifier. */
    public void presentCard(String identifier) {
        this.presentedIdentifier = identifier == null ? null : identifier.trim();
    }

    public void removeCard() {
        this.presentedIdentifier = null;
    }

    @Override
    public String description() {
        return "Simulated card reader (RC522 not connected)";
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public String readCardIdentifier() throws HardwareException {
        String identifier = presentedIdentifier;
        if (identifier == null || identifier.isEmpty()) {
            throw new HardwareException("No card detected. Place the card on the reader.");
        }
        return identifier;
    }
}

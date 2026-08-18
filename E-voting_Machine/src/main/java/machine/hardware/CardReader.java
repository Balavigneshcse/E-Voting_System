package machine.hardware;

/**
 * The voter card reader.
 *
 * <p>Exists so the simulation and the real RC522 module named in the quotation are
 * interchangeable. The rest of the terminal only knows that a card produced an identifier;
 * it does not know or care whether that came off a 13.56MHz MIFARE card over SPI or from an
 * operator typing it in.
 */
public interface CardReader {

    /** Shown in the status bar so it is always obvious whether hardware is real. */
    String description();

    boolean isSimulated();

    /**
     * Reads the identifier from the card currently at the reader.
     *
     * @return the card identifier, never null
     * @throws HardwareException if no card is present or the read failed
     */
    String readCardIdentifier() throws HardwareException;
}

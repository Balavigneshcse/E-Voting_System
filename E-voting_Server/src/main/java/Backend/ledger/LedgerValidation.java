package Backend.ledger;

/**
 * Outcome of recomputing the whole ledger from the database.
 *
 * @param valid         true when every block's hash and back-link check out
 * @param totalBlocks   number of blocks examined, genesis included
 * @param firstBadIndex index of the earliest block that failed, or -1 when valid
 * @param message       human-readable summary for the audit screen
 */
public record LedgerValidation(boolean valid, long totalBlocks, long firstBadIndex, String message) {

    public static LedgerValidation ok(long totalBlocks) {
        return new LedgerValidation(true, totalBlocks, -1L,
                "Chain intact. " + totalBlocks + " block(s) verified.");
    }

    public static LedgerValidation broken(long totalBlocks, long badIndex, String reason) {
        return new LedgerValidation(false, totalBlocks, badIndex,
                "Chain integrity failure at block " + badIndex + ": " + reason);
    }
}

package Backend.dto;

/**
 * Confirmation that a vote is recorded.
 *
 * @param receipt     the ballot's identifier, which the voter can look up in the
 *                    public audit log to confirm their vote was counted. Worth being
 *                    explicit about the trade-off: a receipt that identifies a ballot
 *                    makes the election voter-verifiable, but it also lets a voter
 *                    prove how they voted, which is the basis of vote-buying and
 *                    coercion. Production systems solve this with more elaborate
 *                    schemes; verifiability is the priority for this deployment.
 * @param blockNumber index of the ledger block committing to this ballot
 * @param duplicate   true when this was a replay of an already-recorded vote, which is
 *                    the expected outcome when a terminal re-sends a queued vote after
 *                    a network failure
 */
public record VoteReceipt(
        boolean success,
        String  message,
        String  receipt,
        Long    blockNumber,
        String  blockHash,
        String  electionName,
        boolean duplicate) {

    public static VoteReceipt fail(String message) {
        return new VoteReceipt(false, message, null, null, null, null, false);
    }
}

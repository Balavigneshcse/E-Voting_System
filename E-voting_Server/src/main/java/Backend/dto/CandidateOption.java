package Backend.dto;

/**
 * One ballot choice as the terminal renders it.
 *
 * @param slotNumber which physical push button selects this candidate. The quotation
 *        specifies a non-touch display with eight candidate buttons, so slots are
 *        1-based and capped at eight per ballot.
 * @param hasPhoto whether {@code GET /api/candidate/{id}/photo} has an image to return.
 *        The terminal checks this before fetching, rather than requesting an image for
 *        every candidate and handling a 404 for the common case of none uploaded.
 * @param hasSymbol whether {@code GET /api/candidate/{id}/symbol} has an image to return —
 *        the party symbol shown beside the name, the way a physical EVM ballot does.
 */
public record CandidateOption(
        Integer id,
        String  name,
        String  nameTa,
        String  party,
        String  partyTa,
        int     slotNumber,
        boolean hasPhoto,
        boolean hasSymbol) {}

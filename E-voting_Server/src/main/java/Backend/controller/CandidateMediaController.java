package Backend.controller;

import Backend.model.Candidate;
import Backend.repository.CandidateRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Candidate images, for the ballot screen.
 *
 * <p>Kept out of the ballot payload itself ({@code SessionResult}) rather than embedded
 * as base64: a ballot can hold up to eight candidates, and an uploaded photo or symbol
 * can be several megabytes, so inlining every image would multiply the size of a request
 * that is already sealed and signed on every poll. {@link Backend.dto.CandidateOption}
 * instead carries {@code hasPhoto}/{@code hasSymbol} flags, and the terminal fetches only
 * the images that exist, once, caching them for the rest of the polling day — the
 * candidate list on a terminal does not change between voters.
 *
 * <p>Under {@code /api/**}, so {@link Backend.security.MachineAuthenticationFilter}
 * authenticates every request the same way as the rest of the machine API.
 */
@RestController
public class CandidateMediaController {

    private final CandidateRepository candidates;

    public CandidateMediaController(CandidateRepository candidates) {
        this.candidates = candidates;
    }

    @GetMapping("/api/candidate/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable Integer id) {
        return image(id, Candidate::getPhotoData, Candidate::getPhotoType);
    }

    @GetMapping("/api/candidate/{id}/symbol")
    public ResponseEntity<byte[]> symbol(@PathVariable Integer id) {
        return image(id, Candidate::getSymbolData, Candidate::getSymbolType);
    }

    private ResponseEntity<byte[]> image(Integer id,
                                        java.util.function.Function<Candidate, byte[]> data,
                                        java.util.function.Function<Candidate, String> type) {
        Candidate candidate = candidates.findById(id).orElse(null);
        byte[] bytes = candidate == null ? null : data.apply(candidate);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        String contentType = type.apply(candidate);
        return ResponseEntity.ok()
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.IMAGE_JPEG)
                // Candidate images do not change once uploaded during a live election, and
                // every voter at a booth requests the same handful of images — safe to let
                // the terminal cache them for the day rather than refetch per voter.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=" + TimeUnit.HOURS.toSeconds(12))
                .body(bytes);
    }
}

package Backend.controller;

import Backend.dto.SessionResult;
import Backend.dto.SimpleResult;
import Backend.dto.VoteReceipt;
import Backend.model.Machine;
import Backend.security.MachineRequestContext;
import Backend.security.VotePayloadCodec;
import Backend.security.VotePayloadCodec.SealedVote;
import Backend.service.VotingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Group 3 of the machine API: the vote itself.
 *
 * <p>Four endpoints, matching the quotation's VotingController deliverable: session
 * start, cast, cancel, timeout.
 */
@RestController
@RequestMapping("/api")
public class VotingController {

    private final VotingService    voting;
    private final VotePayloadCodec payloads;

    public VotingController(VotingService voting, VotePayloadCodec payloads) {
        this.voting   = voting;
        this.payloads = payloads;
    }

    /**
     * API 8 — opens a voting session and returns the voter's ballot.
     *
     * <p>Requires the biometric token minted by {@code /api/voter/verify-fingerprint}.
     * Without it there is no session, and without a session there is no vote.
     */
    @PostMapping("/session/start")
    public ResponseEntity<SessionResult> start(@RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        String voterId        = body.get("voterId");
        String biometricToken = body.get("biometricToken");

        if (voterId == null || voterId.isBlank()) {
            return ResponseEntity.badRequest().body(SessionResult.fail("voterId is required."));
        }
        return ResponseEntity.ok(voting.startSession(
                voterId, biometricToken, MachineRequestContext.requireMachineId(request)));
    }

    /**
     * API 9 — records a vote from an encrypted payload.
     *
     * <p>The chosen candidate arrives sealed with AES-256-GCM under a key only this
     * terminal and the vote handler hold, so the ballot choice never appears in plaintext
     * to anything between the TLS edge and this method — not to a reverse proxy, not to
     * request logging.
     *
     * <p>Safe to call repeatedly with the same payload. The idempotency key inside means
     * a vote the terminal queued during a network outage can be resent freely: the first
     * delivery counts and later ones return the same receipt. That is what allows a
     * terminal to hold a vote until the server is reachable without risking a double count.
     */
    @PostMapping("/vote/cast")
    public ResponseEntity<VoteReceipt> cast(@RequestBody Map<String, String> body,
                                            HttpServletRequest request) {
        Machine machine = MachineRequestContext.require(request);

        SealedVote sealed;
        try {
            sealed = payloads.open(machine, body.get("payload"));
        } catch (VotePayloadCodec.InvalidPayloadException e) {
            return ResponseEntity.badRequest().body(VoteReceipt.fail(e.getMessage()));
        }

        VoteReceipt receipt = voting.castVote(machine.getMachineId(), sealed);
        return ResponseEntity.ok(receipt);
    }

    /** API 10 — the voter pressed Cancel. */
    @PostMapping("/session/cancel")
    public ResponseEntity<SimpleResult> cancel(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(voting.closeSession(
                body.get("sessionToken"), "Cancelled by the voter."));
    }

    /** API 11 — the voter walked away and the terminal's idle timer expired. */
    @PostMapping("/session/timeout")
    public ResponseEntity<SimpleResult> timeout(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(voting.closeSession(
                body.get("sessionToken"), "Timed out at the terminal."));
    }
}

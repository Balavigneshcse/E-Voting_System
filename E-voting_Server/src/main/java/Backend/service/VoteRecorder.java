package Backend.service;

import Backend.ledger.LedgerBlock;
import Backend.ledger.VoteLedger;
import Backend.model.Ballot;
import Backend.model.VoterTurnout;
import Backend.model.VotingSession;
import Backend.repository.BallotRepository;
import Backend.repository.VoterTurnoutRepository;
import Backend.repository.VotingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single atomic write that records a vote.
 *
 * <p>Four things must happen together or not at all:
 * <ol>
 *   <li>the voter is marked as having voted, in {@code voter_turnout};</li>
 *   <li>the anonymous ballot is stored, in {@code ballots};</li>
 *   <li>a block committing to that ballot is appended to the ledger;</li>
 *   <li>the voting session is spent.</li>
 * </ol>
 *
 * <p>Previously the ballot went to PostgreSQL while the block went to an in-memory
 * list, so a restart or a mid-write failure left the two permanently inconsistent.
 * Keeping all four in one transaction is what makes the ledger a meaningful audit of
 * the vote table rather than a parallel story about it.
 *
 * <p>This lives in its own bean on purpose. {@link VotingService} needs to catch the
 * unique-constraint violation that enforces one-vote-per-voter, and a constraint
 * violation caught <em>inside</em> the transaction that raised it leaves that
 * transaction rollback-only. The caller must therefore sit outside this boundary,
 * which means crossing a real Spring proxy rather than self-invoking.
 */
@Service
public class VoteRecorder {

    private static final Logger log = LoggerFactory.getLogger(VoteRecorder.class);

    private final VoterTurnoutRepository  turnout;
    private final BallotRepository        ballots;
    private final VotingSessionRepository sessions;
    private final VoteLedger              ledger;

    public VoteRecorder(VoterTurnoutRepository turnout,
                        BallotRepository ballots,
                        VotingSessionRepository sessions,
                        VoteLedger ledger) {
        this.turnout  = turnout;
        this.ballots  = ballots;
        this.sessions = sessions;
        this.ledger   = ledger;
    }

    /**
     * Records the vote atomically.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the voter has
     *         already voted in this election, or this idempotency key was already used.
     *         Both are enforced by database unique constraints rather than by a
     *         preceding read, so the guarantee holds under concurrent requests from
     *         different booths.
     */
    @Transactional
    public RecordedVote record(VotingSession session,
                               Integer candidateId,
                               String electionType,
                               Integer constituencyId,
                               String machineId,
                               String idempotencyKey) {

        turnout.saveAndFlush(new VoterTurnout(
                session.getVoterId(), session.getElectionId(), machineId));

        Ballot ballot = ballots.saveAndFlush(new Ballot(
                session.getElectionId(), electionType, candidateId,
                constituencyId, machineId, idempotencyKey));

        LedgerBlock block = ledger.append(ballot);

        session.setUsed(true);
        sessions.save(session);

        log.info("Vote recorded: election {}, block {}, terminal {}.",
                session.getElectionId(), block.getBlockIndex(), machineId);

        return new RecordedVote(ballot, block);
    }

    /** A stored ballot together with the ledger block that commits to it. */
    public record RecordedVote(Ballot ballot, LedgerBlock block) {}
}

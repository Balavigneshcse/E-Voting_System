package Backend.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * One block in the vote ledger, persisted so the chain survives a restart.
 *
 * <p>The previous implementation kept blocks in an in-memory {@code ArrayList}
 * seeded with a genesis block in the constructor. Restarting the server reset
 * the chain to a single block while the vote rows stayed in PostgreSQL, so the
 * audit log and the database diverged permanently and the tamper check became
 * meaningless. Blocks now live in {@code ledger_blocks}, which rejects UPDATE
 * and DELETE at the database level.
 *
 * <p>Two other changes matter:
 * <ul>
 *   <li>A block references a {@link Backend.model.Ballot} by UUID and carries no
 *       voter identity. The old block stored {@code voterId} beside the candidate
 *       name, so the audit log itself revealed who voted for whom.</li>
 *   <li>The hash covers only stored, deterministic fields, so the entire chain
 *       can be recomputed from the table alone. The old hash included a
 *       {@code LocalDateTime} captured at construction time, which was never
 *       persisted anywhere.</li>
 * </ul>
 */
@Entity
@Immutable
@Table(name = "ledger_blocks")
public class LedgerBlock {

    /** Hash placeholder for the genesis block, which has no predecessor. */
    public static final String GENESIS_PREVIOUS_HASH = "0";

    private static final String FIELD_SEPARATOR = "|";

    @Id
    @Column(name = "block_index", nullable = false, updatable = false)
    private Long blockIndex;

    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    private String previousHash;

    @Column(nullable = false, updatable = false, length = 64)
    private String hash;

    @Column(name = "ballot_uuid", updatable = false, length = 36)
    private String ballotUuid;

    @Column(name = "election_id", updatable = false)
    private Integer electionId;

    @Column(name = "candidate_id", updatable = false)
    private Integer candidateId;

    @Column(name = "constituency_id", updatable = false)
    private Integer constituencyId;

    @Column(name = "machine_id", updatable = false, length = 64)
    private String machineId;

    @Column(name = "cast_at_hour", updatable = false)
    private LocalDateTime castAtHour;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Required by JPA. Not for application use. */
    protected LedgerBlock() {}

    private LedgerBlock(Long blockIndex,
                        String previousHash,
                        String ballotUuid,
                        Integer electionId,
                        Integer candidateId,
                        Integer constituencyId,
                        String machineId,
                        LocalDateTime castAtHour) {
        this.blockIndex     = blockIndex;
        this.previousHash   = previousHash;
        this.ballotUuid     = ballotUuid;
        this.electionId     = electionId;
        this.candidateId    = candidateId;
        this.constituencyId = constituencyId;
        this.machineId      = machineId;
        this.castAtHour     = castAtHour;
        this.hash           = computeHash();
    }

    public static LedgerBlock genesis() {
        return new LedgerBlock(0L, GENESIS_PREVIOUS_HASH, null, null, null, null, null, null);
    }

    public static LedgerBlock forBallot(long blockIndex, String previousHash, Backend.model.Ballot ballot) {
        return new LedgerBlock(
                blockIndex,
                previousHash,
                ballot.getBallotUuid(),
                ballot.getElectionId(),
                ballot.getCandidateId(),
                ballot.getConstituencyId(),
                ballot.getMachineId(),
                ballot.getCastAtHour());
    }

    /**
     * Recomputes this block's hash from its stored fields.
     *
     * <p>Comparing the result against {@link #getHash()} is how tampering is
     * detected: any edit to a payload column changes the recomputed hash, and
     * repairing that hash then breaks the next block's {@code previousHash} link.
     */
    public String computeHash() {
        String payload = String.join(FIELD_SEPARATOR,
                String.valueOf(blockIndex),
                nullSafe(previousHash),
                nullSafe(ballotUuid),
                nullSafe(electionId),
                nullSafe(candidateId),
                nullSafe(constituencyId),
                nullSafe(machineId),
                nullSafe(castAtHour));
        return sha256Hex(payload);
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    public boolean isGenesis() { return blockIndex != null && blockIndex == 0L; }

    public Long          getBlockIndex()     { return blockIndex; }
    public String        getPreviousHash()   { return previousHash; }
    public String        getHash()           { return hash; }
    public String        getBallotUuid()     { return ballotUuid; }
    public Integer       getElectionId()     { return electionId; }
    public Integer       getCandidateId()    { return candidateId; }
    public Integer       getConstituencyId() { return constituencyId; }
    public String        getMachineId()      { return machineId; }
    public LocalDateTime getCastAtHour()     { return castAtHour; }
    public LocalDateTime getCreatedAt()      { return createdAt; }
}

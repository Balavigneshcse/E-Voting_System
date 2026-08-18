package Backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * An anonymous ballot: what was voted for, with no link back to who voted.
 *
 * <p>Ballot secrecy is structural here, not a policy. There is deliberately no
 * {@code voterId} field and no association to {@link Voter}. The fact that a
 * voter has voted is recorded separately in {@link VoterTurnout}, which in turn
 * holds no candidate. Neither table alone can reveal a voter's choice.
 *
 * <p>{@code castAtHour} is truncated to the hour so the two tables cannot be
 * re-linked by matching timestamps.
 *
 * <p>The entity exposes no setters and is marked {@link Immutable}, so Hibernate
 * will never issue an UPDATE for it. The database backs this up with a trigger
 * that rejects UPDATE and DELETE outright.
 */
@Entity
@Immutable
@Table(name = "ballots")
public class Ballot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ballot_uuid", nullable = false, updatable = false, length = 36)
    private String ballotUuid;

    @Column(name = "election_id", nullable = false, updatable = false)
    private Integer electionId;

    @Column(name = "election_type", nullable = false, updatable = false, length = 20)
    private String electionType;

    @Column(name = "candidate_id", nullable = false, updatable = false)
    private Integer candidateId;

    @Column(name = "constituency_id", updatable = false)
    private Integer constituencyId;

    @Column(name = "machine_id", updatable = false, length = 64)
    private String machineId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "cast_at_hour", nullable = false, updatable = false)
    private LocalDateTime castAtHour;

    /** Required by JPA. Not for application use. */
    protected Ballot() {}

    public Ballot(Integer electionId,
                  String electionType,
                  Integer candidateId,
                  Integer constituencyId,
                  String machineId,
                  String idempotencyKey) {
        this.ballotUuid     = UUID.randomUUID().toString();
        this.electionId     = electionId;
        this.electionType   = electionType;
        this.candidateId    = candidateId;
        this.constituencyId = constituencyId;
        this.machineId      = machineId;
        this.idempotencyKey = idempotencyKey;
        this.castAtHour     = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
    }

    public Long          getId()             { return id; }
    public String        getBallotUuid()     { return ballotUuid; }
    public Integer       getElectionId()     { return electionId; }
    public String        getElectionType()   { return electionType; }
    public Integer       getCandidateId()    { return candidateId; }
    public Integer       getConstituencyId() { return constituencyId; }
    public String        getMachineId()      { return machineId; }
    public String        getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCastAtHour()     { return castAtHour; }
}

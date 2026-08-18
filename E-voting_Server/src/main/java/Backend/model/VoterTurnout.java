package Backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * Records that a voter has voted in an election — never what they voted for.
 *
 * <p>The {@code (voter_id, election_id)} unique constraint is the authoritative
 * one-vote-per-voter guarantee. Because it lives in the database rather than in
 * Java, it holds regardless of which booth the voter walks into and regardless
 * of how many server instances are running. That is what makes "vote at any
 * booth" safe.
 *
 * <p>Immutable by construction and by database trigger.
 */
@Entity
@Immutable
@Table(name = "voter_turnout")
public class VoterTurnout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voter_id", nullable = false, updatable = false)
    private String voterId;

    @Column(name = "election_id", nullable = false, updatable = false)
    private Integer electionId;

    @Column(name = "machine_id", updatable = false, length = 64)
    private String machineId;

    @Column(name = "voted_at", nullable = false, updatable = false)
    private LocalDateTime votedAt;

    /** Required by JPA. Not for application use. */
    protected VoterTurnout() {}

    public VoterTurnout(String voterId, Integer electionId, String machineId) {
        this.voterId    = voterId;
        this.electionId = electionId;
        this.machineId  = machineId;
        this.votedAt    = LocalDateTime.now();
    }

    public Long          getId()         { return id; }
    public String        getVoterId()    { return voterId; }
    public Integer       getElectionId() { return electionId; }
    public String        getMachineId()  { return machineId; }
    public LocalDateTime getVotedAt()    { return votedAt; }
}

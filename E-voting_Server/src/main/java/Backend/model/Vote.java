package Backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class Vote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voter_id")
    private String voterId;

    @Column(name = "candidate_id")
    private Integer candidateId;

    @Column(name = "candidate_name")
    private String candidateName;

    @Column(name = "election_id")
    private Integer electionId;

    @Column(name = "election_type")
    private String electionType;

    @Column(name = "constituency_id")
    private Integer constituencyId;

    @Column(name = "municipality_tier")
    private Integer municipalityTier;

    @Column(name = "voted_at")
    private LocalDateTime votedAt = LocalDateTime.now();

    public Long    getId()              { return id; }
    public String  getVoterId()         { return voterId; }
    public Integer getCandidateId()     { return candidateId; }
    public String  getCandidateName()   { return candidateName; }
    public Integer getElectionId()      { return electionId; }
    public String  getElectionType()    { return electionType; }
    public Integer getConstituencyId()   { return constituencyId; }
    public Integer getMunicipalityTier()  { return municipalityTier; }
    public LocalDateTime getVotedAt()   { return votedAt; }

    public void setVoterId(String v)        { this.voterId = v; }
    public void setCandidateId(Integer c)   { this.candidateId = c; }
    public void setCandidateName(String c)  { this.candidateName = c; }
    public void setElectionId(Integer e)    { this.electionId = e; }
    public void setElectionType(String t)   { this.electionType = t; }
    public void setConstituencyId(Integer c)   { this.constituencyId = c; }
    public void setMunicipalityTier(Integer t) { this.municipalityTier = t; }
    public void setCandidate(String c)         { this.candidateName = c; }
    public String getCandidate()               { return candidateName; }
}
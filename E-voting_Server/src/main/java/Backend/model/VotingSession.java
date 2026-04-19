package Backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "voting_sessions")
public class VotingSession {

    @Id
    @Column(length = 100)
    private String id = UUID.randomUUID().toString();

    @Column(name = "voter_id")
    private String voterId;

    @Column(name = "session_token", length = 100)
    private String sessionToken;

    @Column(name = "election_id")
    private Integer electionId;

    @Column(name = "biometric_verified")
    private Boolean biometricVerified = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    private Boolean used = false;

    public String  getId()                 { return id; }
    public String  getVoterId()            { return voterId; }
    public String  getSessionToken()       { return sessionToken; }
    public Integer getElectionId()         { return electionId; }
    public Boolean getBiometricVerified()  { return biometricVerified; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getExpiresAt()    { return expiresAt; }
    public Boolean getUsed()              { return used; }

    public void setVoterId(String v)            { this.voterId = v; }
    public void setSessionToken(String t)       { this.sessionToken = t; }
    public void setElectionId(Integer e)        { this.electionId = e; }
    public void setBiometricVerified(Boolean b) { this.biometricVerified = b; }
    public void setExpiresAt(LocalDateTime e)   { this.expiresAt = e; }
    public void setUsed(Boolean u)              { this.used = u; }
}
package Backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A short-lived authorisation to cast one vote.
 *
 * <p>{@code biometricVerified} may only be set by consuming a
 * {@link BiometricVerification} token, which in turn only exists after a real
 * fingerprint match. Session creation used to set this flag by itself, which is why
 * the fingerprint step could be skipped entirely.
 */
@Entity
@Table(name = "voting_sessions")
public class VotingSession {

    @Id
    @Column(length = 100)
    private String id = UUID.randomUUID().toString();

    @Column(name = "voter_id", nullable = false)
    private String voterId;

    @Column(name = "session_token", length = 100, nullable = false)
    private String sessionToken;

    @Column(name = "election_id")
    private Integer electionId;

    @Column(name = "biometric_verified", nullable = false)
    private Boolean biometricVerified = false;

    /** Which terminal opened this session, so a vote can be attributed to a booth. */
    @Column(name = "machine_id", length = 64)
    private String machineId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Boolean used = false;

    protected VotingSession() {}

    public VotingSession(String voterId, String sessionToken, Integer electionId,
                         String machineId, LocalDateTime expiresAt) {
        this.voterId      = voterId;
        this.sessionToken = sessionToken;
        this.electionId   = electionId;
        this.machineId    = machineId;
        this.expiresAt    = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt == null || expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isSpent() {
        return Boolean.TRUE.equals(used);
    }

    public boolean isBiometricallyVerified() {
        return Boolean.TRUE.equals(biometricVerified);
    }

    public long secondsRemaining() {
        if (expiresAt == null) {
            return 0;
        }
        return Math.max(0, java.time.Duration.between(LocalDateTime.now(), expiresAt).toSeconds());
    }

    public String        getId()                { return id; }
    public String        getVoterId()           { return voterId; }
    public String        getSessionToken()      { return sessionToken; }
    public Integer       getElectionId()        { return electionId; }
    public Boolean       getBiometricVerified() { return biometricVerified; }
    public String        getMachineId()         { return machineId; }
    public LocalDateTime getCreatedAt()         { return createdAt; }
    public LocalDateTime getExpiresAt()         { return expiresAt; }
    public Boolean       getUsed()              { return used; }

    /**
     * Marks biometrics as satisfied.
     *
     * <p>Call this only after consuming a {@link BiometricVerification} token bound to
     * the same voter and terminal.
     */
    public void confirmBiometrics() { this.biometricVerified = true; }

    public void setUsed(Boolean used) { this.used = used; }
}

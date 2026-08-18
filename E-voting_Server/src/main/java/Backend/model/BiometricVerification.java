package Backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Proof that a specific voter passed the fingerprint check on a specific machine.
 *
 * <p>The fingerprint check happens before a voting session exists, so its result
 * has to be carried forward somehow. Previously it was not carried at all:
 * {@code POST /api/session/start} set {@code biometric_verified = true} on the
 * session itself, which meant a caller could skip the fingerprint endpoint
 * entirely and still vote.
 *
 * <p>Now a successful match mints a short-lived, single-use token recorded here.
 * Session creation must present it, and the token is bound to the same voter and
 * the same machine that performed the scan.
 *
 * <p>Only the SHA-256 of the token is stored, so a database leak does not yield
 * usable tokens.
 */
@Entity
@Table(name = "biometric_verifications")
public class BiometricVerification {

    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "voter_id", nullable = false)
    private String voterId;

    @Column(name = "machine_id", nullable = false, length = 64)
    private String machineId;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    protected BiometricVerification() {}

    public BiometricVerification(String tokenHash, String voterId, String machineId, LocalDateTime expiresAt) {
        this.tokenHash  = tokenHash;
        this.voterId    = voterId;
        this.machineId  = machineId;
        this.verifiedAt = LocalDateTime.now();
        this.expiresAt  = expiresAt;
    }

    public boolean isUsable() {
        return consumedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    public String        getTokenHash()  { return tokenHash; }
    public String        getVoterId()    { return voterId; }
    public String        getMachineId()  { return machineId; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public LocalDateTime getExpiresAt()  { return expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }

    public void consume() { this.consumedAt = LocalDateTime.now(); }
}

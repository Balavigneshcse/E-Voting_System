package Backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A machine JWT tracked by its {@code jti} claim.
 *
 * <p>JWTs are self-validating, which normally makes them impossible to revoke
 * before expiry. Recording the jti here means a compromised or retired terminal
 * can be cut off immediately: the token verifier rejects any jti that is missing,
 * expired, or revoked.
 */
@Entity
@Table(name = "machine_tokens")
public class MachineToken {

    @Id
    @Column(length = 36)
    private String jti;

    @Column(name = "machine_id", nullable = false, length = 64)
    private String machineId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected MachineToken() {}

    public MachineToken(String jti, String machineId, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.jti       = jti;
        this.machineId = machineId;
        this.issuedAt  = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    public String        getJti()       { return jti; }
    public String        getMachineId() { return machineId; }
    public LocalDateTime getIssuedAt()  { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }

    public void revoke() { this.revokedAt = LocalDateTime.now(); }
}

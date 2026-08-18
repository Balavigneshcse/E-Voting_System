package Backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A registered voting terminal.
 *
 * <p>Replaces the previous arrangement where every machine shared one secret and
 * issued tokens lived in a static in-memory set. Each terminal now has its own
 * provisioning secret, stored only as a PBKDF2 hash, and can be revoked
 * individually without affecting the others.
 */
@Entity
@Table(name = "machines")
public class Machine {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE  = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @Column(name = "machine_id", length = 64)
    private String machineId;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(name = "booth_name", length = 150)
    private String boothName;

    /** PBKDF2-WithHmacSHA256 verifier for the one-time provisioning secret. Never the secret itself. */
    @Column(name = "secret_verifier", length = 128)
    private String secretVerifier;

    @Column(name = "secret_salt", length = 64)
    private String secretSalt;

    /** Per-machine HMAC signing key, AES-256-GCM encrypted under the server master key. */
    @Column(name = "signing_key_cipher", length = 256)
    private String signingKeyCipher;

    @Column(name = "signing_key_iv", length = 64)
    private String signingKeyIv;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Machine() {}

    public Machine(String machineId, String label, String boothName) {
        this.machineId = machineId;
        this.label     = label;
        this.boothName = boothName;
    }

    public boolean isActive()      { return STATUS_ACTIVE.equals(status); }
    public boolean isProvisioned() { return secretVerifier != null && secretSalt != null; }

    public String        getMachineId()        { return machineId; }
    public String        getLabel()            { return label; }
    public String        getBoothName()        { return boothName; }
    public String        getSecretVerifier()   { return secretVerifier; }
    public String        getSecretSalt()       { return secretSalt; }
    public String        getSigningKeyCipher() { return signingKeyCipher; }
    public String        getSigningKeyIv()     { return signingKeyIv; }
    public String        getStatus()           { return status; }
    public LocalDateTime getRegisteredAt()     { return registeredAt; }
    public LocalDateTime getLastSeenAt()       { return lastSeenAt; }
    public LocalDateTime getRevokedAt()        { return revokedAt; }
    public LocalDateTime getCreatedAt()        { return createdAt; }

    public void setLabel(String label)         { this.label = label; }
    public void setBoothName(String boothName) { this.boothName = boothName; }

    /**
     * Stores a fresh credential set and activates the terminal.
     *
     * @param secretVerifier   PBKDF2 verifier for the one-time provisioning secret
     * @param secretSalt       salt used to produce the verifier
     * @param signingKeyCipher per-machine HMAC key, encrypted under the master key
     * @param signingKeyIv     IV for that ciphertext
     */
    public void provision(String secretVerifier, String secretSalt,
                          String signingKeyCipher, String signingKeyIv) {
        this.secretVerifier   = secretVerifier;
        this.secretSalt       = secretSalt;
        this.signingKeyCipher = signingKeyCipher;
        this.signingKeyIv     = signingKeyIv;
        this.status           = STATUS_ACTIVE;
        this.revokedAt        = null;
    }

    public void revoke() {
        this.status    = STATUS_REVOKED;
        this.revokedAt = LocalDateTime.now();
    }

    public void markRegistered() {
        this.registeredAt = LocalDateTime.now();
        this.lastSeenAt   = this.registeredAt;
    }

    public void touch() {
        this.lastSeenAt = LocalDateTime.now();
    }
}

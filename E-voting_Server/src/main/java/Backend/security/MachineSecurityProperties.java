package Backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunable security settings for the machine-facing API, bound from
 * {@code evoting.security.*}.
 *
 * <p>Every secret here is expected to arrive from the environment. The previous
 * configuration committed the database password, the admin password, the machine
 * secret and the admin API key straight into {@code application.properties}.
 */
@ConfigurationProperties(prefix = "evoting.security")
public class MachineSecurityProperties {

    /**
     * Base64 32-byte key that encrypts per-machine signing keys at rest.
     * Losing it means every terminal has to be re-provisioned; leaking it means
     * every terminal's signing key is exposed.
     */
    private String masterKey;

    /** Base64 32-byte key used to sign machine JWTs. */
    private String jwtSecret;

    /** How long an issued machine token stays valid. One polling day, with headroom. */
    private Duration tokenTtl = Duration.ofHours(18);

    /**
     * How far a signed request's timestamp may drift from server time. Narrow
     * enough to bound replay, wide enough to survive modest clock skew on a Pi
     * without a real-time clock battery.
     */
    private Duration signatureTolerance = Duration.ofSeconds(120);

    /** Reject machine requests that arrive without a valid HMAC signature. */
    private boolean requireSignature = true;

    /** Reject machine requests that did not arrive over HTTPS. */
    private boolean requireTls = true;

    /** Lifetime of the single-use token minted by a successful fingerprint match. */
    private Duration biometricTokenTtl = Duration.ofSeconds(180);

    /** Lifetime of a voting session, matching the 2-minute terminal timeout. */
    private Duration votingSessionTtl = Duration.ofSeconds(120);

    /**
     * How long after it was cast a queued vote may still be delivered.
     *
     * <p>A terminal that loses the network holds the vote and retries. This bounds how
     * stale such a delivery can be — long enough to cover an outage lasting most of a
     * polling day, short enough that a vote cannot surface days later.
     */
    private Duration queuedVoteMaxAge = Duration.ofHours(24);

    /**
     * Server-side pepper mixed into simulated fingerprint templates. Keeping it
     * server-side means a stored template cannot be recomputed from a voter ID
     * alone by anyone who only has database access.
     */
    private String fingerprintPepper;

    /**
     * Enables simulated NFC and fingerprint hardware. When true, voters without a
     * real enrolled template are auto-enrolled with a deterministic simulated one,
     * so the biometric code path still runs for real. Must be false in production.
     */
    private boolean simulationEnabled = true;

    /** One-time secret used to provision terminals still in PENDING state. */
    private String machineBootstrapSecret;

    public String   getMasterKey()                    { return masterKey; }
    public void     setMasterKey(String v)            { this.masterKey = v; }
    public String   getJwtSecret()                    { return jwtSecret; }
    public void     setJwtSecret(String v)            { this.jwtSecret = v; }
    public Duration getTokenTtl()                     { return tokenTtl; }
    public void     setTokenTtl(Duration v)           { this.tokenTtl = v; }
    public Duration getSignatureTolerance()           { return signatureTolerance; }
    public void     setSignatureTolerance(Duration v) { this.signatureTolerance = v; }
    public boolean  isRequireSignature()              { return requireSignature; }
    public void     setRequireSignature(boolean v)    { this.requireSignature = v; }
    public boolean  isRequireTls()                    { return requireTls; }
    public void     setRequireTls(boolean v)          { this.requireTls = v; }
    public Duration getBiometricTokenTtl()            { return biometricTokenTtl; }
    public void     setBiometricTokenTtl(Duration v)  { this.biometricTokenTtl = v; }
    public Duration getVotingSessionTtl()             { return votingSessionTtl; }
    public void     setVotingSessionTtl(Duration v)   { this.votingSessionTtl = v; }
    public Duration getQueuedVoteMaxAge()             { return queuedVoteMaxAge; }
    public void     setQueuedVoteMaxAge(Duration v)   { this.queuedVoteMaxAge = v; }
    public String   getFingerprintPepper()            { return fingerprintPepper; }
    public void     setFingerprintPepper(String v)    { this.fingerprintPepper = v; }
    public boolean  isSimulationEnabled()             { return simulationEnabled; }
    public void     setSimulationEnabled(boolean v)   { this.simulationEnabled = v; }
    public String   getMachineBootstrapSecret()       { return machineBootstrapSecret; }
    public void     setMachineBootstrapSecret(String v) { this.machineBootstrapSecret = v; }
}

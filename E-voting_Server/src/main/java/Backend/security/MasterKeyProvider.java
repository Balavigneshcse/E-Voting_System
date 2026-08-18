package Backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Supplies the two root secrets: the master key that encrypts per-machine signing
 * keys, and the key that signs machine JWTs.
 *
 * <p>Both must be 32 bytes, base64 encoded. If either is missing the application
 * refuses to start outside development rather than silently falling back to a
 * default — a hardcoded fallback key is indistinguishable from no encryption at all.
 *
 * <p>"Outside development" specifically means anything other than an explicit
 * {@code dev} profile. Spring's own {@code default} profile — what a deployment gets
 * when nobody passes {@code --spring.profiles.active} at all — does not count as
 * development here. Treating an unset profile as safe for ephemeral keys would mean
 * the one deployment mode nobody remembers to configure explicitly is also the one
 * that silently gets weak, restart-losing crypto instead of a startup failure telling
 * them to fix it.
 */
@Component
public class MasterKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyProvider.class);

    private final byte[] masterKey;
    private final byte[] jwtKey;

    public MasterKeyProvider(MachineSecurityProperties properties, Environment environment) {
        boolean devMode = environment.matchesProfiles("dev");

        this.masterKey = resolve(properties.getMasterKey(), "evoting.security.master-key",
                "EVOTING_MASTER_KEY", devMode);
        this.jwtKey = resolve(properties.getJwtSecret(), "evoting.security.jwt-secret",
                "EVOTING_JWT_SECRET", devMode);
    }

    private byte[] resolve(String configured, String propertyName, String envName, boolean devMode) {
        if (configured == null || configured.isBlank()) {
            if (!devMode) {
                throw new IllegalStateException(
                        propertyName + " is not set. Provide a base64 32-byte key via " + envName + ".");
            }
            log.warn("""
                    {} is not set — deriving an ephemeral development key.
                    Machine credentials will not survive a restart. Set {} to a base64 \
                    32-byte value before running a real election.""", propertyName, envName);
            return CryptoSupport.randomBytes(CryptoSupport.AES_KEY_BYTES);
        }

        byte[] decoded;
        try {
            decoded = CryptoSupport.fromBase64(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " must be valid base64", e);
        }
        if (decoded.length != CryptoSupport.AES_KEY_BYTES) {
            throw new IllegalStateException(
                    propertyName + " must decode to exactly " + CryptoSupport.AES_KEY_BYTES
                            + " bytes, got " + decoded.length);
        }
        return decoded;
    }

    public byte[] masterKey() { return masterKey.clone(); }
    public byte[] jwtKey()    { return jwtKey.clone(); }
}

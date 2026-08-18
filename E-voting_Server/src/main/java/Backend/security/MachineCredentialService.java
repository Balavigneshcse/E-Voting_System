package Backend.security;

import Backend.model.Machine;
import Backend.repository.MachineRepository;
import Backend.repository.MachineTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Issues and validates per-terminal credentials.
 *
 * <p>Provisioning produces two independent things:
 * <ul>
 *   <li>a one-time <em>provisioning secret</em>, shown to the operator once and
 *       kept only as a PBKDF2 verifier — this is what the terminal presents to
 *       register;</li>
 *   <li>a random 32-byte <em>signing key</em>, encrypted at rest under the master
 *       key and handed to the terminal once over TLS at registration — this is
 *       what signs every later request.</li>
 * </ul>
 *
 * <p>Separating them means a leaked database yields neither a usable registration
 * secret nor a usable signing key without also compromising the master key.
 */
@Service
public class MachineCredentialService {

    /** HKDF context labels. Distinct labels guarantee distinct subkeys. */
    private static final String SIGNING_KEY_INFO = "evoting/machine-request-signature/v1";
    private static final String PAYLOAD_KEY_INFO = "evoting/vote-payload/v1";

    private static final int PROVISIONING_SECRET_BYTES = 24;
    private static final int SALT_BYTES                = 16;

    private static final Logger log = LoggerFactory.getLogger(MachineCredentialService.class);

    private final MachineRepository      machines;
    private final MachineTokenRepository tokens;
    private final MasterKeyProvider      keys;

    public MachineCredentialService(MachineRepository machines,
                                    MachineTokenRepository tokens,
                                    MasterKeyProvider keys) {
        this.machines = machines;
        this.tokens   = tokens;
        this.keys     = keys;
    }

    /**
     * Generates a fresh credential set for a terminal and returns the one-time
     * provisioning secret. The caller must show it to the operator immediately;
     * it cannot be recovered afterwards.
     *
     * <p>Any tokens previously issued to this terminal are revoked, so re-provisioning
     * is also the way to cut off a terminal that has gone missing.
     */
    @Transactional
    public String provision(Machine machine) {
        String provisioningSecret = CryptoSupport.randomToken(PROVISIONING_SECRET_BYTES);
        byte[] salt       = CryptoSupport.randomBytes(SALT_BYTES);
        byte[] signingKey = CryptoSupport.randomBytes(CryptoSupport.AES_KEY_BYTES);
        byte[] iv         = CryptoSupport.randomBytes(CryptoSupport.GCM_IV_BYTES);

        machine.provision(
                CryptoSupport.pbkdf2Base64(provisioningSecret, salt),
                CryptoSupport.base64(salt),
                CryptoSupport.encryptWithIv(keys.masterKey(), iv, signingKey),
                CryptoSupport.base64(iv));

        machines.save(machine);
        int revoked = tokens.revokeAllForMachine(machine.getMachineId(), LocalDateTime.now());
        log.info("Provisioned terminal {} ({} previously issued token(s) revoked).",
                machine.getMachineId(), revoked);

        return provisioningSecret;
    }

    /**
     * Provisions a terminal with a caller-supplied secret instead of a generated one.
     *
     * <p>Used only for bootstrap provisioning from an environment variable, so a fresh
     * install can bring its terminals online without hand-editing the database.
     */
    @Transactional
    public void provisionWithSecret(Machine machine, String provisioningSecret) {
        byte[] salt       = CryptoSupport.randomBytes(SALT_BYTES);
        byte[] signingKey = CryptoSupport.randomBytes(CryptoSupport.AES_KEY_BYTES);
        byte[] iv         = CryptoSupport.randomBytes(CryptoSupport.GCM_IV_BYTES);

        machine.provision(
                CryptoSupport.pbkdf2Base64(provisioningSecret, salt),
                CryptoSupport.base64(salt),
                CryptoSupport.encryptWithIv(keys.masterKey(), iv, signingKey),
                CryptoSupport.base64(iv));

        machines.save(machine);
        tokens.revokeAllForMachine(machine.getMachineId(), LocalDateTime.now());
    }

    /** Constant-time check of a presented provisioning secret. */
    public boolean matchesProvisioningSecret(Machine machine, String presentedSecret) {
        if (!machine.isProvisioned() || presentedSecret == null || presentedSecret.isBlank()) {
            return false;
        }
        String candidate = CryptoSupport.pbkdf2Base64(
                presentedSecret, CryptoSupport.fromBase64(machine.getSecretSalt()));
        return CryptoSupport.constantTimeEquals(candidate, machine.getSecretVerifier());
    }

    /** Decrypts the terminal's root signing key. Only handed out at registration. */
    public byte[] signingKey(Machine machine) {
        if (machine.getSigningKeyCipher() == null || machine.getSigningKeyIv() == null) {
            throw new IllegalStateException(
                    "Terminal " + machine.getMachineId() + " has no signing key; re-provision it.");
        }
        return CryptoSupport.decryptWithIv(
                keys.masterKey(),
                CryptoSupport.fromBase64(machine.getSigningKeyIv()),
                machine.getSigningKeyCipher());
    }

    /** Subkey that verifies request signatures. */
    public byte[] requestSignatureKey(Machine machine) {
        return CryptoSupport.hkdf(signingKey(machine),
                CryptoSupport.utf8(machine.getMachineId()),
                SIGNING_KEY_INFO,
                CryptoSupport.AES_KEY_BYTES);
    }

    /** Subkey that decrypts encrypted vote payloads. */
    public byte[] payloadKey(Machine machine) {
        return CryptoSupport.hkdf(signingKey(machine),
                CryptoSupport.utf8(machine.getMachineId()),
                PAYLOAD_KEY_INFO,
                CryptoSupport.AES_KEY_BYTES);
    }
}

package Backend.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cryptographic primitives shared by machine authentication, request signing and
 * payload encryption.
 *
 * <p>Everything here uses the JDK's own providers — no third-party crypto library.
 * That matters because the voting terminal mirrors these exact routines and has to
 * stay a lightweight single jar, per the hardware quotation where the Raspberry Pi
 * is specified as a thin client.
 *
 * <p>Algorithm choices:
 * <ul>
 *   <li>AES-256-GCM for confidentiality with built-in authentication, so a
 *       tampered ciphertext fails to decrypt rather than decrypting to garbage.</li>
 *   <li>HMAC-SHA256 for request signatures.</li>
 *   <li>HKDF-SHA256 to derive purpose-specific keys from one machine key, so the
 *       signing key and the encryption key are never the same bytes.</li>
 *   <li>PBKDF2-SHA256, 210,000 iterations, for the provisioning secret verifier.</li>
 * </ul>
 */
public final class CryptoSupport {

    public static final int    AES_KEY_BYTES      = 32;   // AES-256
    public static final int    GCM_IV_BYTES       = 12;   // 96-bit nonce, the GCM standard
    public static final int    GCM_TAG_BITS       = 128;
    public static final int    PBKDF2_ITERATIONS  = 210_000;
    public static final int    PBKDF2_KEY_BYTES   = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int    HASH_BYTES     = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoSupport() {}

    // ── Random ──────────────────────────────────────────────────────────────

    public static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    /** URL-safe random token, useful for nonces and opaque handles. */
    public static String randomToken(int byteLength) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(byteLength));
    }

    // ── Encoding ────────────────────────────────────────────────────────────

    public static String base64(byte[] data)        { return Base64.getEncoder().encodeToString(data); }
    public static byte[] fromBase64(String encoded) { return Base64.getDecoder().decode(encoded); }

    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    // ── Hashing ─────────────────────────────────────────────────────────────

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(byte[] data)   { return hex(sha256(data)); }
    public static String sha256Hex(String data)   { return sha256Hex(utf8(data)); }

    // ── MAC ─────────────────────────────────────────────────────────────────

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String hmacSha256Hex(byte[] key, String data) {
        return hex(hmacSha256(key, utf8(data)));
    }

    // ── Key derivation ──────────────────────────────────────────────────────

    /**
     * HKDF-SHA256 (RFC 5869). Splits one machine key into distinct subkeys so the
     * signing key and the payload key cannot be substituted for each other.
     *
     * @param info context label identifying the subkey's purpose
     */
    public static byte[] hkdf(byte[] inputKey, byte[] salt, String info, int outputLength) {
        if (outputLength > 255 * HASH_BYTES) {
            throw new IllegalArgumentException("HKDF output too long: " + outputLength);
        }
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_BYTES] : salt;

        // Extract
        byte[] pseudoRandomKey = hmacSha256(effectiveSalt, inputKey);

        // Expand
        byte[] output   = new byte[outputLength];
        byte[] previous = new byte[0];
        int    written  = 0;
        for (int counter = 1; written < outputLength; counter++) {
            byte[] infoBytes = utf8(info);
            ByteBuffer input = ByteBuffer.allocate(previous.length + infoBytes.length + 1);
            input.put(previous).put(infoBytes).put((byte) counter);
            previous = hmacSha256(pseudoRandomKey, input.array());

            int chunk = Math.min(previous.length, outputLength - written);
            System.arraycopy(previous, 0, output, written, chunk);
            written += chunk;
        }
        return output;
    }

    /** PBKDF2 verifier for the one-time machine provisioning secret. */
    public static String pbkdf2Base64(String secret, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    secret.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BYTES * 8);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return base64(derived);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        }
    }

    // ── AES-256-GCM ─────────────────────────────────────────────────────────

    /** Encrypts and prefixes the IV, returning {@code base64(iv || ciphertext || tag)}. */
    public static String encryptToEnvelope(byte[] key, byte[] plaintext) {
        byte[] iv     = randomBytes(GCM_IV_BYTES);
        byte[] cipher = aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext);
        return base64(ByteBuffer.allocate(iv.length + cipher.length).put(iv).put(cipher).array());
    }

    /** Reverses {@link #encryptToEnvelope}. Throws if the ciphertext was tampered with. */
    public static byte[] decryptFromEnvelope(byte[] key, String envelope) {
        byte[] raw = fromBase64(envelope);
        if (raw.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("Encrypted envelope is truncated");
        }
        byte[] iv     = Arrays.copyOfRange(raw, 0, GCM_IV_BYTES);
        byte[] cipher = Arrays.copyOfRange(raw, GCM_IV_BYTES, raw.length);
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, cipher);
    }

    /** Encrypts with a caller-supplied IV, for at-rest values that store the IV separately. */
    public static String encryptWithIv(byte[] key, byte[] iv, byte[] plaintext) {
        return base64(aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext));
    }

    public static byte[] decryptWithIv(byte[] key, byte[] iv, String ciphertextBase64) {
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, fromBase64(ciphertextBase64));
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM operation failed", e);
        }
    }

    // ── Comparison ──────────────────────────────────────────────────────────

    /**
     * Constant-time comparison. Using {@code String.equals} here would leak how
     * many leading characters of a secret were guessed correctly via timing.
     */
    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(utf8(left), utf8(right));
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left, right);
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

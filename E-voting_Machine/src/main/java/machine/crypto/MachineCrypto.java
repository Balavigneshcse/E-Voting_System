package machine.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The terminal's half of the cryptography, mirroring the server's {@code CryptoSupport}.
 *
 * <p>Deliberately JDK-only. The quotation specifies the Raspberry Pi as a thin client
 * running "lightweight Java HTTP client only", so the terminal jar keeps its single
 * dependency (Jackson) and pulls in no crypto library. Everything here is
 * {@code javax.crypto} and {@code java.security}, both in the base JDK.
 *
 * <p>These routines must produce byte-identical output to the server's. Any divergence
 * surfaces as an opaque signature mismatch or a decryption failure rather than a clear
 * error, so the algorithms and the exact input encodings are duplicated intentionally
 * rather than approximated:
 * <ul>
 *   <li>hex is lowercase, two characters per byte;</li>
 *   <li>HKDF-SHA256 per RFC 5869, extract then expand;</li>
 *   <li>AES-256-GCM with a 12-byte IV prefixed to the ciphertext and a 128-bit tag.</li>
 * </ul>
 */
public final class MachineCrypto {

    public static final int AES_KEY_BYTES = 32;
    public static final int GCM_IV_BYTES  = 12;
    public static final int GCM_TAG_BITS  = 128;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int    HASH_BYTES     = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private MachineCrypto() {}

    // ── Random ──────────────────────────────────────────────────────────────

    public static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    /** Nonce for request signing. Must be unique per request or the server rejects it. */
    public static String randomNonce() {
        return hex(randomBytes(16));
    }

    public static SecureRandom secureRandom() {
        return RANDOM;
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

    public static String sha256Hex(byte[] data) { return hex(sha256(data)); }

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
     * HKDF-SHA256. Splits the one signing key the server hands over at registration into
     * separate subkeys for request signing and for payload encryption, so neither can be
     * substituted for the other.
     */
    public static byte[] hkdf(byte[] inputKey, byte[] salt, String info, int outputLength) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_BYTES] : salt;
        byte[] pseudoRandomKey = hmacSha256(effectiveSalt, inputKey);

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

    // ── AES-256-GCM ─────────────────────────────────────────────────────────

    /** Produces {@code base64(iv || ciphertext || tag)}, the envelope the server expects. */
    public static String encryptToEnvelope(byte[] key, byte[] plaintext) {
        byte[] iv     = randomBytes(GCM_IV_BYTES);
        byte[] cipher = aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext);
        return base64(ByteBuffer.allocate(iv.length + cipher.length).put(iv).put(cipher).array());
    }

    public static byte[] decryptFromEnvelope(byte[] key, String envelope) {
        byte[] raw = fromBase64(envelope);
        if (raw.length <= GCM_IV_BYTES) {
            throw new IllegalArgumentException("Encrypted envelope is truncated");
        }
        byte[] iv     = Arrays.copyOfRange(raw, 0, GCM_IV_BYTES);
        byte[] cipher = Arrays.copyOfRange(raw, GCM_IV_BYTES, raw.length);
        return aesGcm(Cipher.DECRYPT_MODE, key, iv, cipher);
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

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

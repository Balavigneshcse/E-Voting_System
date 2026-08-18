package Backend.service;

import Backend.model.BiometricVerification;
import Backend.model.Voter;
import Backend.repository.BiometricVerificationRepository;
import Backend.repository.VoterRepository;
import Backend.security.CryptoSupport;
import Backend.security.MachineSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fingerprint enrollment and verification, including the hardware simulation.
 *
 * <h2>What was wrong before</h2>
 * The terminal sent {@code "DEMO-HASH-" + System.currentTimeMillis()} and the server
 * compared it byte-for-byte against {@code voters.fingerprint_template}. Every seeded
 * voter had a null template and {@code fingerprint_enrolled = false}, so the match
 * could never succeed and no voter could get past the fingerprint screen. Meanwhile
 * {@code POST /api/session/start} marked the session biometrically verified all by
 * itself, so skipping the fingerprint endpoint entirely worked fine. The check was
 * simultaneously impossible to pass and trivial to bypass.
 *
 * <h2>How simulation works now</h2>
 * It mirrors the NFC simulation. A physical voter card carries an identifier the
 * reader presents to the server; a simulated card presents the same identifier typed
 * in by the operator. Likewise, each voter has a derived <em>fingerprint sample
 * code</em> that stands in for what the MFS100 scanner would produce. The terminal
 * receives it as part of the simulated card read and submits it on the fingerprint
 * screen, so the voter still gets a single-touch experience.
 *
 * <p>Crucially the server-side path is real: the submitted sample is hashed with a
 * server-held pepper and compared against the enrolled template in constant time. The
 * terminal cannot assert "the fingerprint matched"; it can only submit a sample and be
 * told. Swapping in the real scanner means changing what fills the sample field —
 * nothing in this class or in the endpoints changes.
 *
 * <h2>Where the seam is for real hardware</h2>
 * A genuine fingerprint comparison is a fuzzy minutiae match performed by the scanner
 * vendor's matcher SDK, not an equality test on a hash. When the MFS100 arrives,
 * {@link #verify} is the single place to delegate to that SDK. Everything downstream —
 * the single-use token, the session binding — stays as is.
 */
@Service
public class BiometricService {

    /** Length of the simulated sample code, in hex characters. */
    private static final int SIMULATED_CODE_LENGTH = 12;

    private static final Logger log = LoggerFactory.getLogger(BiometricService.class);

    private final VoterRepository                 voters;
    private final BiometricVerificationRepository verifications;
    private final MachineSecurityProperties       properties;

    public BiometricService(VoterRepository voters,
                            BiometricVerificationRepository verifications,
                            MachineSecurityProperties properties) {
        this.voters        = voters;
        this.verifications = verifications;
        this.properties    = properties;
    }

    public boolean simulationEnabled() {
        return properties.isSimulationEnabled();
    }

    // ── Enrollment ──────────────────────────────────────────────────────────

    /**
     * The stand-in for a scanned fingerprint, derived per voter.
     *
     * <p>Derived from the voter ID under the server pepper rather than stored, so it
     * needs no schema change and cannot be recomputed by anyone who only has the
     * database. Returned to a terminal only while simulation is enabled.
     */
    public String simulatedSampleCode(String voterId) {
        String mac = CryptoSupport.hmacSha256Hex(pepper(), "fingerprint-sample:" + voterId);
        return mac.substring(0, SIMULATED_CODE_LENGTH).toUpperCase();
    }

    /**
     * Enrolls the simulated template for a voter who has none, so the real
     * verification path has something to compare against.
     *
     * <p>No-op when simulation is disabled: in production an unenrolled voter must
     * fail verification rather than be silently given a credential.
     */
    @Transactional
    public void ensureEnrolled(Voter voter) {
        if (!properties.isSimulationEnabled() || Boolean.TRUE.equals(voter.getFingerprintEnrolled())) {
            return;
        }
        voter.setFingerprintTemplate(templateFor(simulatedSampleCode(voter.getVoterId())));
        voter.setFingerprintEnrolled(true);
        voters.save(voter);
        log.info("Simulated fingerprint enrolled for voter {}.", voter.getVoterId());
    }

    /** Hashes a sample into a stored template. The pepper keeps this off-limits to a DB-only attacker. */
    public byte[] templateFor(String sample) {
        return CryptoSupport.sha256(CryptoSupport.utf8("fp-template:" + pepperString() + ":" + sample));
    }

    // ── Verification ────────────────────────────────────────────────────────

    /**
     * Compares a submitted sample against the voter's enrolled template.
     *
     * <p>Constant-time, and it never trusts a client-side assertion of success.
     */
    public boolean verify(Voter voter, String submittedSample) {
        if (!Boolean.TRUE.equals(voter.getFingerprintEnrolled())
                || voter.getFingerprintTemplate() == null) {
            log.warn("Fingerprint verification attempted for unenrolled voter {}.", voter.getVoterId());
            return false;
        }
        if (submittedSample == null || submittedSample.isBlank()) {
            return false;
        }
        // Real hardware seam: replace with the scanner SDK's minutiae match.
        return CryptoSupport.constantTimeEquals(
                voter.getFingerprintTemplate(), templateFor(submittedSample.trim().toUpperCase()));
    }

    /**
     * Mints the single-use proof that this voter passed biometrics on this terminal.
     *
     * <p>Only the token's hash is stored, so a leaked table yields nothing usable.
     *
     * @return the token to hand back to the terminal
     */
    @Transactional
    public String issueVerificationToken(String voterId, String machineId) {
        String token = CryptoSupport.randomToken(32);
        verifications.save(new BiometricVerification(
                CryptoSupport.sha256Hex(token),
                voterId,
                machineId,
                LocalDateTime.now().plus(properties.getBiometricTokenTtl())));
        return token;
    }

    /**
     * Consumes a biometric token, enforcing that it is unused, unexpired and bound to
     * the same voter and terminal that produced it.
     *
     * <p>The voter and machine binding is what stops a token minted for one voter from
     * being used to open a session for another.
     *
     * @return empty when the token is absent, expired, already used, or bound elsewhere
     */
    @Transactional
    public Optional<BiometricVerification> consume(String token, String voterId, String machineId) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<BiometricVerification> found = verifications.findById(CryptoSupport.sha256Hex(token))
                .filter(BiometricVerification::isUsable)
                .filter(record -> record.getVoterId().equals(voterId))
                .filter(record -> record.getMachineId().equals(machineId));

        found.ifPresent(record -> {
            record.consume();
            verifications.save(record);
        });
        return found;
    }

    @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void pruneExpiredVerifications() {
        int removed = verifications.deleteExpiredBefore(LocalDateTime.now().minusHours(1));
        if (removed > 0) {
            log.debug("Pruned {} expired biometric verification(s).", removed);
        }
    }

    // ── Pepper ──────────────────────────────────────────────────────────────

    private String pepperString() {
        String configured = properties.getFingerprintPepper();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "evoting.security.fingerprint-pepper is not set. Provide it via "
                            + "EVOTING_FINGERPRINT_PEPPER; enrolled templates depend on it and "
                            + "changing it invalidates every enrollment.");
        }
        return configured;
    }

    private byte[] pepper() {
        return CryptoSupport.utf8(pepperString());
    }
}

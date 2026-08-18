package Backend.service;

import Backend.model.Machine;
import Backend.repository.MachineRepository;
import Backend.security.CryptoSupport;
import Backend.security.MachineCredentialService;
import Backend.security.MachineJwtService;
import Backend.security.MachineSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Terminal lifecycle: create, provision, register, revoke.
 *
 * <p>Supporting many terminals against one server was previously nominal — all of them
 * shared a single secret, tokens lived in a static in-memory set that no restart
 * survived and no operator could revoke, and the machine ID was logged but never
 * stored. A stolen terminal could not be cut off without changing the secret on every
 * other terminal, and a recorded vote could not be traced to the booth that cast it.
 */
@Service
public class MachineService {

    private static final Logger log = LoggerFactory.getLogger(MachineService.class);

    private final MachineRepository        machines;
    private final MachineCredentialService credentials;
    private final MachineJwtService        jwtService;
    private final MachineSecurityProperties properties;

    public MachineService(MachineRepository machines,
                          MachineCredentialService credentials,
                          MachineJwtService jwtService,
                          MachineSecurityProperties properties) {
        this.machines    = machines;
        this.credentials = credentials;
        this.jwtService  = jwtService;
        this.properties  = properties;
    }

    // ── Boot-time provisioning ──────────────────────────────────────────────

    /**
     * Provisions any terminal still PENDING using the bootstrap secret, if one is
     * configured.
     *
     * <p>This exists so a fresh install can be brought up without hand-editing the
     * database, while still keeping the credential out of source control: the operator
     * supplies it as an environment variable. Without it, PENDING terminals simply
     * cannot register until an admin provisions them from the dashboard.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void provisionPendingTerminals() {
        String bootstrapSecret = properties.getMachineBootstrapSecret();
        if (bootstrapSecret == null || bootstrapSecret.isBlank()) {
            long pending = machines.findByStatus(Machine.STATUS_PENDING).size();
            if (pending > 0) {
                log.info("{} terminal(s) awaiting provisioning. Provision them from the admin "
                        + "dashboard, or set EVOTING_MACHINE_BOOTSTRAP_SECRET.", pending);
            }
            return;
        }
        for (Machine machine : machines.findByStatus(Machine.STATUS_PENDING)) {
            credentials.provisionWithSecret(machine, bootstrapSecret);
            log.info("Terminal {} provisioned from the bootstrap secret.", machine.getMachineId());
        }
    }

    // ── Registration, called by the terminal ────────────────────────────────

    /**
     * Authenticates a terminal by its one-time provisioning secret and issues working
     * credentials.
     *
     * <p>This is the only machine endpoint that is not itself signed, because the
     * terminal has no signing key until this call returns one. It is therefore the one
     * place where TLS is doing the heavy lifting, and the returned signing key must
     * never travel over plain HTTP.
     */
    @Transactional
    public RegistrationOutcome register(String machineId, String provisioningSecret) {
        Optional<Machine> found = machines.findById(machineId == null ? "" : machineId.trim());
        if (found.isEmpty()) {
            log.warn("Registration attempt for unknown terminal '{}'.", machineId);
            return RegistrationOutcome.rejected(
                    "This terminal is not registered with the server. Ask the election officer to add it.");
        }
        Machine machine = found.get();

        if (Machine.STATUS_REVOKED.equals(machine.getStatus())) {
            log.warn("Registration attempt by revoked terminal {}.", machineId);
            return RegistrationOutcome.rejected("This terminal has been revoked.");
        }
        if (!machine.isProvisioned()) {
            return RegistrationOutcome.rejected(
                    "This terminal has not been provisioned yet. Ask the election officer to "
                            + "issue its provisioning secret.");
        }
        if (!credentials.matchesProvisioningSecret(machine, provisioningSecret)) {
            log.warn("Registration attempt with an incorrect secret for terminal {}.", machineId);
            return RegistrationOutcome.rejected("Provisioning secret is incorrect.");
        }

        MachineJwtService.IssuedToken token = jwtService.issue(machine.getMachineId());
        String signingKey = CryptoSupport.base64(credentials.signingKey(machine));

        machine.markRegistered();
        machines.save(machine);

        log.info("Terminal {} ({}) registered successfully.", machine.getMachineId(), machine.getLabel());
        return new RegistrationOutcome(true,
                "Terminal registered.",
                token.token(),
                token.secondsUntilExpiry(),
                signingKey,
                machine.getMachineId(),
                machine.getLabel(),
                machine.getBoothName());
    }

    // ── Admin operations ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Machine> listAll() {
        return machines.findAllByOrderByMachineIdAsc();
    }

    @Transactional
    public Machine create(String machineId, String label, String boothName) {
        if (machines.existsById(machineId)) {
            throw new IllegalArgumentException("A terminal with ID '" + machineId + "' already exists.");
        }
        return machines.save(new Machine(machineId, label, boothName));
    }

    /**
     * Issues a fresh provisioning secret, shown to the operator once.
     *
     * <p>Also the remedy for a lost or stolen terminal: re-provisioning rotates the
     * signing key and revokes every token the old credential produced.
     */
    @Transactional
    public String reprovision(String machineId) {
        Machine machine = machines.findById(machineId)
                .orElseThrow(() -> new IllegalArgumentException("No such terminal: " + machineId));
        return credentials.provision(machine);
    }

    @Transactional
    public void revoke(String machineId) {
        Machine machine = machines.findById(machineId)
                .orElseThrow(() -> new IllegalArgumentException("No such terminal: " + machineId));
        machine.revoke();
        machines.save(machine);
        int revokedTokens = jwtService.revokeAllFor(machineId);
        log.warn("Terminal {} revoked. {} active token(s) invalidated.", machineId, revokedTokens);
    }

    /**
     * Credentials handed to a terminal on successful registration.
     *
     * @param signingKeyBase64 the terminal's HMAC key. Sent exactly once, over TLS, and
     *                         stored by the terminal for signing later requests.
     */
    public record RegistrationOutcome(
            boolean success,
            String  message,
            String  machineToken,
            long    expiresInSeconds,
            String  signingKeyBase64,
            String  machineId,
            String  label,
            String  boothName) {

        static RegistrationOutcome rejected(String message) {
            return new RegistrationOutcome(false, message, null, 0, null, null, null, null);
        }
    }
}

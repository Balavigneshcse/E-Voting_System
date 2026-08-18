package Backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Rejects a signed request that has been seen before.
 *
 * <p>A valid signature only proves a request was produced by the terminal, not that
 * it is fresh. Without this, anyone able to capture one signed
 * {@code POST /api/vote/cast} could resend it and add votes. Each request carries a
 * single-use nonce; the first insert wins and any repeat hits the primary key.
 *
 * <p>Stored in the database rather than in memory so the guarantee survives a
 * restart and holds across server instances.
 */
@Service
public class ReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplayGuard.class);

    private final JdbcTemplate jdbc;
    private final MachineSecurityProperties properties;

    public ReplayGuard(JdbcTemplate jdbc, MachineSecurityProperties properties) {
        this.jdbc       = jdbc;
        this.properties = properties;
    }

    /**
     * Claims a nonce for this machine.
     *
     * <p>Runs in its own transaction so a rejected replay does not poison the
     * caller's transaction, and so a nonce stays claimed even if the request it
     * belongs to later fails.
     *
     * @return true when the nonce is fresh, false when it has already been used
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String nonce, String machineId) {
        try {
            jdbc.update("INSERT INTO machine_nonces (nonce, machine_id, seen_at) VALUES (?, ?, ?)",
                    nonce, machineId, LocalDateTime.now());
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("Replay rejected: nonce already used by machine {}.", machineId);
            return false;
        }
    }

    /**
     * Drops nonces older than the signature freshness window, since a request that
     * stale is rejected on its timestamp anyway. Without this the table grows for
     * every request ever made.
     */
    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @Transactional
    public void pruneExpiredNonces() {
        Duration retention = properties.getSignatureTolerance().multipliedBy(4);
        int removed = jdbc.update("DELETE FROM machine_nonces WHERE seen_at < ?",
                LocalDateTime.now().minus(retention));
        if (removed > 0) {
            log.debug("Pruned {} expired request nonce(s).", removed);
        }
    }
}

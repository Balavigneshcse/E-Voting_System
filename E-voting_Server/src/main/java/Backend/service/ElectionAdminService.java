package Backend.service;

import Backend.dto.SimpleResult;
import Backend.model.Election;
import Backend.repository.ElectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Opening and closing polling.
 *
 * <p>Notably absent: any way to reset or clear votes. The previous version exposed
 * {@code POST /admin/reset-election/{id}} and {@code POST /admin/reset-election}, both
 * of which called {@code deleteByElectionId} on the vote table, and shipped two SQL
 * procedures that did {@code DELETE FROM vote}. Recorded votes are now immutable, so
 * those paths are gone rather than merely discouraged. Re-running a demo means restoring
 * a database snapshot, which is the honest way to say "these votes are final".
 */
@Service
public class ElectionAdminService {

    private static final List<String> SUPPORTED_TYPES = List.of("PM", "CM");

    private static final Logger log = LoggerFactory.getLogger(ElectionAdminService.class);

    private final ElectionRepository elections;
    private final JdbcTemplate       jdbc;

    public ElectionAdminService(ElectionRepository elections, JdbcTemplate jdbc) {
        this.elections = elections;
        this.jdbc      = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Election> listSupportedElections() {
        return elections.findAll().stream()
                .filter(election -> SUPPORTED_TYPES.contains(election.getType()))
                .toList();
    }

    /**
     * Opens one election and closes every other, so exactly one is ever active.
     *
     * <p>A single active election is what lets a terminal work out which ballot to show
     * without being told by its own configuration — which in turn is what allows a voter
     * to use any booth.
     */
    @Transactional
    public SimpleResult open(Integer electionId) {
        Election target = elections.findById(electionId).orElse(null);
        if (target == null) {
            return SimpleResult.fail("Election not found.");
        }
        if (!SUPPORTED_TYPES.contains(target.getType())) {
            return SimpleResult.fail(
                    "Election type '" + target.getType() + "' is no longer supported. "
                            + "Only Lok Sabha (PM) and Vidhan Sabha (CM) elections can be opened.");
        }

        elections.findAll().forEach(election -> {
            if (Boolean.TRUE.equals(election.getIsActive())) {
                election.setIsActive(false);
                elections.save(election);
            }
        });
        target.setIsActive(true);
        elections.save(target);

        log.info("Polling opened for '{}' (id {}).", target.getName(), target.getId());
        return SimpleResult.ok("Polling is open for " + target.getName() + ".");
    }

    @Transactional
    public SimpleResult close(Integer electionId) {
        Election target = elections.findById(electionId).orElse(null);
        if (target == null) {
            return SimpleResult.fail("Election not found.");
        }
        if (!Boolean.TRUE.equals(target.getIsActive())) {
            return SimpleResult.ok("Polling was already closed for " + target.getName() + ".");
        }
        target.setIsActive(false);
        elections.save(target);

        log.info("Polling closed for '{}' (id {}).", target.getName(), target.getId());
        return SimpleResult.ok("Polling is closed for " + target.getName()
                + ". Recorded votes remain available for counting.");
    }

    /**
     * Creates the next election cycle for a type and opens it, closing whatever was open
     * before — the PM and CM progressions are independent of each other, each counting up
     * from its own most recent cycle, but within one type this is still exactly the same
     * "one active election" rule {@link #open} already enforces, just started fresh for a
     * new round rather than pointed at an existing row.
     *
     * <p>A fresh CM election opens with every state gated closed (see
     * {@code V8__cm_election_state_gating.sql}) — a new assembly election does not inherit
     * the previous cycle's rollout.
     */
    @Transactional
    public SimpleResult nextElection(String type) {
        if (!SUPPORTED_TYPES.contains(type)) {
            return SimpleResult.fail("Election type '" + type + "' is not supported.");
        }
        List<Election> existing = elections.findByTypeOrderByElectionCycleDesc(type);
        int nextCycle = existing.isEmpty() || existing.get(0).getElectionCycle() == null
                ? 1 : existing.get(0).getElectionCycle() + 1;

        Election next = new Election();
        next.setType(type);
        next.setName(("PM".equals(type) ? "Lok Sabha Election" : "Vidhan Sabha Election")
                + " — Cycle " + nextCycle);
        next.setElectionCycle(nextCycle);
        next.setIsActive(false);
        elections.save(next);

        SimpleResult opened = open(next.getId());
        if (!opened.success()) {
            return opened;
        }
        log.info("Advanced {} to cycle {} (election id {}).", type, nextCycle, next.getId());
        return SimpleResult.ok("Started " + next.getName() + " and opened it for polling.");
    }

    /** Which states a CM election is currently open to. Meaningless for a PM election,
     *  which is never state-gated. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openStates(Integer electionId) {
        return jdbc.queryForList("""
                SELECT s.id AS state_id, s.name AS state_name, eos.opened_at
                FROM   states s
                JOIN   election_open_states eos
                       ON eos.state_id = s.id AND eos.election_id = ?
                ORDER  BY s.name
                """, electionId);
    }

    public SimpleResult openState(Integer electionId, Integer stateId) {
        Election election = elections.findById(electionId).orElse(null);
        if (election == null) {
            return SimpleResult.fail("Election not found.");
        }
        if (!"CM".equals(election.getType())) {
            return SimpleResult.fail("Only CM elections are opened state by state — a PM election is national.");
        }
        jdbc.update("""
                INSERT INTO election_open_states (election_id, state_id)
                VALUES (?, ?)
                ON CONFLICT (election_id, state_id) DO NOTHING
                """, electionId, stateId);
        log.info("Opened state {} for CM election {}.", stateId, electionId);
        return SimpleResult.ok("Polling opened for this state.");
    }

    public SimpleResult closeState(Integer electionId, Integer stateId) {
        jdbc.update("DELETE FROM election_open_states WHERE election_id = ? AND state_id = ?",
                electionId, stateId);
        log.info("Closed state {} for CM election {}.", stateId, electionId);
        return SimpleResult.ok("Polling closed for this state.");
    }
}

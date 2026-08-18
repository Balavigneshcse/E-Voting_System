package Backend.ledger;

import Backend.model.Ballot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Append-only, hash-chained ledger over anonymous ballots.
 *
 * <p>Each block commits to the hash of its predecessor, so altering a recorded
 * ballot invalidates every block after it. Combined with the database triggers
 * that reject UPDATE and DELETE on {@code ledger_blocks} and {@code ballots},
 * this gives tamper-evidence rather than merely tamper-discouragement.
 *
 * <p>Worth being precise about what this does and does not provide: it is a local
 * hash chain, not a distributed consensus network. It makes silent modification
 * of recorded votes detectable by anyone who can recompute the chain. It does not
 * encrypt anything — confidentiality on the wire is handled separately by TLS and
 * per-machine payload encryption.
 *
 * <p>Appends are serialised with a PostgreSQL transaction-scoped advisory lock
 * rather than a Java {@code synchronized} block, so the sequence stays correct
 * even if a second server instance is started against the same database.
 */
@Service
public class VoteLedger {

    /** Arbitrary but fixed key identifying the ledger-append advisory lock. */
    private static final long ADVISORY_LOCK_KEY = 8_314_777_001L;

    private static final Logger log = LoggerFactory.getLogger(VoteLedger.class);

    private final LedgerBlockRepository blocks;
    private final JdbcTemplate jdbc;

    public VoteLedger(LedgerBlockRepository blocks, JdbcTemplate jdbc) {
        this.blocks = blocks;
        this.jdbc   = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialiseChain() {
        if (blocks.count() == 0) {
            blocks.save(LedgerBlock.genesis());
            log.info("Vote ledger initialised with genesis block.");
        }
        LedgerValidation validation = validate();
        if (validation.valid()) {
            log.info("Vote ledger loaded: {} block(s), chain intact.", validation.totalBlocks());
        } else {
            log.error("VOTE LEDGER INTEGRITY FAILURE: {}", validation.message());
        }
    }

    /**
     * Appends a block for a freshly recorded ballot.
     *
     * <p>Joins the caller's transaction on purpose: the ballot row, the turnout row
     * and the ledger block must all commit together, or none of them should. This
     * is what stopped the ledger and the vote table from drifting apart.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerBlock append(Ballot ballot) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ADVISORY_LOCK_KEY);

        long nextIndex = blocks.findMaxBlockIndex() + 1;
        String previousHash = blocks.findFirstByOrderByBlockIndexDesc()
                .map(LedgerBlock::getHash)
                .orElse(LedgerBlock.GENESIS_PREVIOUS_HASH);

        LedgerBlock block = LedgerBlock.forBallot(nextIndex, previousHash, ballot);
        return blocks.saveAndFlush(block);
    }

    /** Recomputes every block from the database and reports the first inconsistency. */
    @Transactional(readOnly = true)
    public LedgerValidation validate() {
        List<LedgerBlock> chain = blocks.findAllByOrderByBlockIndexAsc();
        if (chain.isEmpty()) {
            return LedgerValidation.ok(0);
        }

        LedgerBlock genesis = chain.get(0);
        if (!genesis.isGenesis()) {
            return LedgerValidation.broken(chain.size(), genesis.getBlockIndex(),
                    "chain does not start at the genesis block");
        }

        for (int i = 0; i < chain.size(); i++) {
            LedgerBlock current = chain.get(i);

            if (!current.getHash().equals(current.computeHash())) {
                return LedgerValidation.broken(chain.size(), current.getBlockIndex(),
                        "stored hash does not match the recomputed hash, so its contents were altered");
            }
            if (current.getBlockIndex() != i) {
                return LedgerValidation.broken(chain.size(), current.getBlockIndex(),
                        "block index is out of sequence, so a block was removed");
            }
            if (i == 0) {
                continue;
            }
            if (!current.getPreviousHash().equals(chain.get(i - 1).getHash())) {
                return LedgerValidation.broken(chain.size(), current.getBlockIndex(),
                        "previous-hash link is broken");
            }
        }
        return LedgerValidation.ok(chain.size());
    }

    @Transactional(readOnly = true)
    public List<LedgerBlock> fullChain() {
        return blocks.findAllByOrderByBlockIndexAsc();
    }

    @Transactional(readOnly = true)
    public long height() {
        return blocks.count();
    }
}

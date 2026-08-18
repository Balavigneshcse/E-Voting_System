package machine.queue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import machine.config.MachineSettings;
import machine.crypto.MachineCrypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Durable local storage for votes awaiting delivery.
 *
 * <p>The previous client kept nothing on disk. A failed {@code castVote} showed a dialog
 * and the vote was gone — the voter had confirmed their choice and it simply never existed.
 * Every confirmed vote now lands here before the terminal reports success, so a network
 * outage, a server restart, or the terminal itself losing power costs nothing.
 *
 * <h2>Encryption at rest</h2>
 * The file is sealed with AES-256-GCM under a key derived from this terminal's provisioning
 * secret. That protects a queued ballot if the microSD card is removed from the Pi. It is
 * not protection against someone who already has the terminal's config file, since the
 * secret is in it — the same trust boundary either way. The point is that a card pulled
 * from a machine does not read out as a list of votes.
 *
 * <h2>Write durability</h2>
 * Writes go to a temporary file which is then moved into place, so a power cut during a
 * write leaves either the old queue or the new one, never a half-written file. The X728 UPS
 * HAT in the quotation exists to make this rare; this makes it survivable.
 */
public class VoteQueue {

    private static final String QUEUE_KEY_INFO = "evoting/local-queue/v1";

    private final Path         file;
    private final byte[]       encryptionKey;
    private final ObjectMapper json;

    /** Guards the file and the in-memory copy together. */
    private final Object lock = new Object();
    private final List<PendingVote> pending = new ArrayList<>();

    public VoteQueue(MachineSettings settings) {
        this.file = settings.pendingVotesFile();
        this.json = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Derived from the provisioning secret rather than from the server-issued signing
        // key, because the queue must be readable at boot before the terminal has managed
        // to register — which is exactly the situation where it holds votes.
        this.encryptionKey = MachineCrypto.hkdf(
                MachineCrypto.sha256(MachineCrypto.utf8(settings.provisioningSecret())),
                MachineCrypto.utf8(settings.machineId()),
                QUEUE_KEY_INFO,
                MachineCrypto.AES_KEY_BYTES);

        loadFromDisk();
    }

    // ── Queue operations ────────────────────────────────────────────────────

    /** Persists a vote before the voter is told it succeeded. */
    public void enqueue(PendingVote vote) throws IOException {
        synchronized (lock) {
            pending.add(vote);
            flushToDisk();
        }
    }

    /** Removes a vote the server has acknowledged. */
    public void remove(String idempotencyKey) {
        synchronized (lock) {
            boolean changed = pending.removeIf(vote -> vote.idempotencyKey().equals(idempotencyKey));
            if (changed) {
                try {
                    flushToDisk();
                } catch (IOException e) {
                    // The vote is already recorded on the server. Failing to prune the local
                    // copy only means one more harmless idempotent retry later.
                    System.err.println("Could not update the local queue file: " + e.getMessage());
                }
            }
        }
    }

    /** Records a failed attempt so backoff and the on-screen count stay accurate. */
    public void recordAttempt(String idempotencyKey) {
        synchronized (lock) {
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).idempotencyKey().equals(idempotencyKey)) {
                    pending.set(i, pending.get(i).withAnotherAttempt());
                    break;
                }
            }
            try {
                flushToDisk();
            } catch (IOException e) {
                System.err.println("Could not update the local queue file: " + e.getMessage());
            }
        }
    }

    public List<PendingVote> snapshot() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(pending));
        }
    }

    public int size() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void loadFromDisk() {
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            String envelope = Files.readString(file).trim();
            if (envelope.isEmpty()) {
                return;
            }
            byte[] plaintext = MachineCrypto.decryptFromEnvelope(encryptionKey, envelope);
            PendingVote[] restored = json.readValue(plaintext, PendingVote[].class);
            pending.clear();
            Collections.addAll(pending, restored);

            if (!pending.isEmpty()) {
                System.out.println("Recovered " + pending.size()
                        + " undelivered vote(s) from the local queue.");
            }
        } catch (Exception e) {
            // Never delete the file on a read failure: it may hold real votes and a wrong
            // key or a partial read must not be allowed to destroy them.
            System.err.println("""
                    Could not read the pending-vote queue. It has been left untouched.
                    If PROVISIONING_SECRET or MACHINE_ID changed, the old queue cannot be \
                    decrypted; restore the previous values to recover it.
                    Cause: """ + e.getMessage());
        }
    }

    private void flushToDisk() throws IOException {
        byte[] plaintext = json.writeValueAsBytes(pending);
        String envelope  = MachineCrypto.encryptToEnvelope(encryptionKey, plaintext);

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, envelope);
        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

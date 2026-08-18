package machine.queue;

import machine.api.ApiResponses;
import machine.api.ServerClient;
import machine.api.ServerUnavailableException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Background delivery of queued votes.
 *
 * <p>Runs on its own daemon thread so a slow or unreachable server never blocks the voter
 * in front of the screen. Each pass walks the queue oldest-first and stops at the first
 * sign the server is still unavailable, rather than hammering it with the whole backlog.
 *
 * <p>Three outcomes per vote:
 * <ul>
 *   <li><b>Accepted</b>, including as a duplicate — drop it from the queue. A duplicate
 *       response means an earlier attempt actually succeeded and only the reply was lost,
 *       which is precisely why the idempotency key exists.</li>
 *   <li><b>Server unavailable</b> — leave it queued and stop this pass.</li>
 *   <li><b>Rejected outright</b> — the server has considered it and said no. Retrying
 *       cannot change that, so it is dropped and reported loudly rather than retried
 *       forever.</li>
 * </ul>
 */
public class VoteSyncWorker {

    private final ServerClient  server;
    private final VoteQueue     queue;
    private final int           retrySeconds;

    /** Notified after every pass so the UI can show the outstanding count. */
    private final Consumer<QueueStatus> statusListener;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "vote-sync");
                thread.setDaemon(true);
                return thread;
            });

    public VoteSyncWorker(ServerClient server, VoteQueue queue,
                          int retrySeconds, Consumer<QueueStatus> statusListener) {
        this.server         = server;
        this.queue          = queue;
        this.retrySeconds   = Math.max(5, retrySeconds);
        this.statusListener = statusListener;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                this::drainQuietly, 2, retrySeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** Prompts an immediate delivery attempt, used right after a vote is confirmed. */
    public void nudge() {
        scheduler.execute(this::drainQuietly);
    }

    private void drainQuietly() {
        try {
            drain();
        } catch (RuntimeException e) {
            System.err.println("Vote sync pass failed: " + e.getMessage());
        }
    }

    private void drain() {
        if (!server.isRegistered()) {
            publishStatus(false, "Terminal not registered with the server.");
            return;
        }
        List<PendingVote> outstanding = queue.snapshot();
        if (outstanding.isEmpty()) {
            publishStatus(true, "All votes delivered.");
            return;
        }

        for (PendingVote vote : outstanding) {
            try {
                ApiResponses.VoteReceipt receipt = server.castVote(vote);

                if (receipt.success()) {
                    queue.remove(vote.idempotencyKey());
                    System.out.println("Queued vote delivered"
                            + (receipt.duplicate() ? " (already recorded)" : "")
                            + ", block " + receipt.blockNumber());
                    continue;
                }

                // A considered refusal. Keeping it would mean retrying forever.
                queue.remove(vote.idempotencyKey());
                System.err.println("Queued vote rejected by the server and discarded: "
                        + receipt.message());

            } catch (ServerUnavailableException e) {
                queue.recordAttempt(vote.idempotencyKey());
                publishStatus(false, "Server unreachable, holding "
                        + queue.size() + " vote(s). Retrying every " + retrySeconds + "s.");
                return;

            } catch (IOException | IllegalStateException e) {
                queue.recordAttempt(vote.idempotencyKey());
                publishStatus(false, "Delivery error, holding " + queue.size() + " vote(s).");
                System.err.println("Could not deliver a queued vote: " + e.getMessage());
                return;
            }
        }
        publishStatus(true, "All votes delivered.");
    }

    private void publishStatus(boolean synced, String message) {
        if (statusListener != null) {
            statusListener.accept(new QueueStatus(synced, queue.size(), message));
        }
    }

    /** What the terminal shows the polling officer about delivery health. */
    public record QueueStatus(boolean allDelivered, int pendingCount, String message) {}
}

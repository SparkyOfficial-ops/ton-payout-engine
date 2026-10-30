package dev.tonpayout.queue;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.chain.BlockInclusion;
import dev.tonpayout.chain.MessageHashes;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutStore.BatchOutcome;
import dev.tonpayout.queue.PayoutStore.SentBatch;

/**
 * After sendBoc the batch is only {@code sent}. This thread asks the chain
 * whether that message hash is already inside a block and then sets {@code confirmed}.
 */
public final class TxPoller {
    private static final Logger LOG = Logger.getLogger(TxPoller.class.getName());

    private final EngineConfig config;
    private final PayoutStore store;
    private final WebhookNotifier webhook;
    private final BlockInclusion inclusion;
    private final SendingRecovery recovery;
    private volatile boolean running;
    private Thread worker;

    public TxPoller(EngineConfig config, PayoutStore store, WebhookNotifier webhook, BlockInclusion inclusion,
            SendingRecovery recovery) {
        this.config = config;
        this.store = store;
        this.webhook = webhook;
        this.inclusion = inclusion;
        this.recovery = recovery;
    }

    public void start() {
        running = true;
        worker = new Thread(this::loop, "tx-poller");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        while (running) {
            try {
                long dueAt = System.currentTimeMillis() - config.confirmDelayMs();
                for (SentBatch batch : store.sentBefore(dueAt)) {
                    check(batch);
                }
                recovery.sweep();
                Thread.sleep(Math.min(1000, config.confirmDelayMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "confirmation poll failed", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void check(SentBatch batch) throws Exception {
        List<String> hashes = MessageHashes.split(batch.txHash());
        if (hashes.isEmpty()) {
            notify(store.markNotInBlock(batch.batchId(), "sent batch has no message hash"));
            return;
        }
        boolean included;
        try {
            included = inclusion.allIncluded(hashes);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "block lookup failed for " + batch.batchId() + ": " + e.getMessage());
            store.delayConfirmation(batch.batchId());
            return;
        }
        if (included) {
            LOG.info("batch " + batch.batchId() + " is in a block");
            notify(store.markConfirmed(batch.batchId()));
            return;
        }
        int next = batch.checks() + 1;
        if (next >= config.confirmAttempts()) {
            LOG.info("batch " + batch.batchId() + " was not found in a block");
            notify(store.markNotInBlock(batch.batchId(), "message was not included in a block"));
            return;
        }
        store.noteConfirmationMiss(batch.batchId());
        LOG.info("batch " + batch.batchId() + " is still outside a block, check " + next);
    }

    private void notify(BatchOutcome outcome) {
        if (!outcome.terminal()) {
            return;
        }
        webhook.batchUpdated(outcome.batchId(), outcome.status(), outcome.count(), outcome.txHash(), outcome.error());
    }
}

package dev.tonpayout.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.chain.BlockInclusion;
import dev.tonpayout.chain.MessageHashes;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutStore.BatchOutcome;
import dev.tonpayout.queue.PayoutStore.StuckSending;

/**
 * Resolves batches left in {@code sending} after a crash.
 * They are not broadcast again. A stored message hash that is already in a block
 * becomes {@code confirmed}; otherwise the batch becomes {@code failed}.
 */
public final class SendingRecovery {
    private static final Logger LOG = Logger.getLogger(SendingRecovery.class.getName());
    private static final String NO_HASH =
            "sending interrupted and the message hash was not stored; not sent again; check the wallet history before retrying with a new X-Idempotency-Key";
    private static final String NOT_IN_BLOCK =
            "sending interrupted and the message was not included in a block; retry with a new X-Idempotency-Key";
    private static final String PARTIAL =
            "sending interrupted after a partial broadcast; already sent transfers were left sent; retry the failed recipients with a new X-Idempotency-Key";

    private final EngineConfig config;
    private final PayoutStore store;
    private final WebhookNotifier webhook;
    private final BlockInclusion inclusion;
    private final Map<String, Long> retryAfter = new HashMap<>();

    public SendingRecovery(EngineConfig config, PayoutStore store, WebhookNotifier webhook, BlockInclusion inclusion) {
        this.config = config;
        this.store = store;
        this.webhook = webhook;
        this.inclusion = inclusion;
    }

    public synchronized List<BatchOutcome> sweep() throws Exception {
        long updatedBefore = System.currentTimeMillis() - config.sendingStaleMs();
        List<BatchOutcome> resolved = new ArrayList<>();
        for (StuckSending batch : store.staleSending(updatedBefore)) {
            BatchOutcome outcome = resolve(batch);
            if (outcome != null && outcome.terminal()) {
                webhook.batchUpdated(outcome.batchId(), outcome.status(), outcome.count(), outcome.txHash(), outcome.error());
                resolved.add(outcome);
            }
        }
        return resolved;
    }

    private BatchOutcome resolve(StuckSending batch) throws Exception {
        long now = System.currentTimeMillis();
        if (retryAfter.getOrDefault(batch.batchId(), 0L) > now) {
            return null;
        }
        List<String> hashes = MessageHashes.split(batch.txHash());
        if (batch.sent() > 0 || hashes.isEmpty()) {
            String error = batch.sent() > 0 ? PARTIAL : NO_HASH;
            LOG.warning("batch " + batch.batchId() + " stuck in sending: " + error);
            return store.recoverFailed(batch.batchId(), error);
        }
        boolean included;
        try {
            included = inclusion.allIncluded(hashes);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "block lookup failed for stuck batch " + batch.batchId() + ": " + e.getMessage());
            retryAfter.put(batch.batchId(), now + 15_000);
            return null;
        }
        if (included) {
            LOG.info("stuck batch " + batch.batchId() + " is in a block");
            return store.recoverConfirmed(batch.batchId());
        }
        LOG.warning("batch " + batch.batchId() + " stuck in sending: " + NOT_IN_BLOCK);
        return store.recoverFailed(batch.batchId(), NOT_IN_BLOCK);
    }
}

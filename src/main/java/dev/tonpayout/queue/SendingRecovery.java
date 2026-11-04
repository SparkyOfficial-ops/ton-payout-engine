package dev.tonpayout.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import dev.tonpayout.chain.BlockInclusion;
import dev.tonpayout.chain.ChainLook;
import dev.tonpayout.chain.MessageHashes;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.log.BatchMdc;
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
    private final BlockInclusion inclusion;
    private final Map<String, Long> retryAfter = new HashMap<>();
    /** Sending batches found at startup. The chain is checked again without the stale wait. */
    private final Set<String> interrupted = new HashSet<>();
    private boolean startupDone;

    public SendingRecovery(EngineConfig config, PayoutStore store, BlockInclusion inclusion) {
        this.config = config;
        this.store = store;
        this.inclusion = inclusion;
    }

    public synchronized List<BatchOutcome> sweep() throws Exception {
        return sweep(new ChainLook(inclusion), !startupDone);
    }

    synchronized List<BatchOutcome> sweep(ChainLook look) throws Exception {
        return sweep(look, false);
    }

    /**
     * @param startup every {@code sending} batch belongs to the previous process,
     *                so the chain is checked now instead of after {@code sendingStaleMs}
     */
    private List<BatchOutcome> sweep(ChainLook look, boolean startup) throws Exception {
        if (startup) {
            startupDone = true;
        }
        long updatedBefore = startup
                ? Long.MAX_VALUE
                : System.currentTimeMillis() - config.sendingStaleMs();
        List<StuckSending> due = new ArrayList<>(store.staleSending(updatedBefore));
        if (startup) {
            interrupted.clear();
            for (StuckSending batch : due) {
                interrupted.add(batch.batchId());
            }
        } else if (!interrupted.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (StuckSending batch : due) {
                seen.add(batch.batchId());
            }
            for (StuckSending batch : store.staleSending(Long.MAX_VALUE)) {
                if (interrupted.contains(batch.batchId()) && seen.add(batch.batchId())) {
                    due.add(batch);
                }
            }
        }
        List<BatchOutcome> resolved = new ArrayList<>();
        for (StuckSending batch : due) {
            BatchOutcome outcome = resolve(batch, look);
            if (outcome != null && outcome.terminal()) {
                interrupted.remove(batch.batchId());
                resolved.add(outcome);
            }
        }
        return resolved;
    }

    private BatchOutcome resolve(StuckSending batch, ChainLook look) throws Exception {
        BatchMdc.set(batch.batchId());
        try {
            return resolveOne(batch, look);
        } finally {
            BatchMdc.clear();
        }
    }

    private BatchOutcome resolveOne(StuckSending batch, ChainLook look) throws Exception {
        long now = System.currentTimeMillis();
        if (retryAfter.getOrDefault(batch.batchId(), 0L) > now) {
            return null;
        }
        List<String> hashes = MessageHashes.split(batch.txHash());
        if (batch.sent() > 0 || hashes.isEmpty()) {
            if (withinTtl(batch)) {
                retryAfter.put(batch.batchId(), now + config.confirmDelayMs());
                return null;
            }
            String error = batch.sent() > 0 ? PARTIAL : NO_HASH;
            LOG.warning("batch " + batch.batchId() + " stuck in sending: " + error);
            return store.recoverFailed(batch.batchId(), error);
        }
        boolean included;
        try {
            included = look.allIncluded(hashes);
        } catch (Exception e) {
            retryAfter.put(batch.batchId(), now + 15_000);
            return null;
        }
        if (included) {
            LOG.info("stuck batch " + batch.batchId() + " is in a block");
            return store.recoverConfirmed(batch.batchId());
        }
        if (withinTtl(batch)) {
            retryAfter.put(batch.batchId(), now + config.confirmDelayMs());
            return null;
        }
        LOG.warning("batch " + batch.batchId() + " stuck in sending: " + NOT_IN_BLOCK);
        return store.recoverFailed(batch.batchId(), NOT_IN_BLOCK);
    }

    private boolean withinTtl(StuckSending batch) {
        return batch.updatedAt() > 0 && System.currentTimeMillis() < batch.updatedAt() + config.messageTtlMs();
    }
}

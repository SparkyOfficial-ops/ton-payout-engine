package dev.tonpayout.queue;

import java.math.BigInteger;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.chain.ChainSender;
import dev.tonpayout.chain.ChainSender.Outgoing;
import dev.tonpayout.chain.TonAddress;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutStore.BatchOutcome;
import dev.tonpayout.queue.PayoutStore.DuplicateIdempotencyKey;
import dev.tonpayout.queue.PayoutStore.IdempotentHit;
import dev.tonpayout.queue.PayoutStore.QueuedTransfer;

public final class PayoutBatcher {
    private static final Logger LOG = Logger.getLogger(PayoutBatcher.class.getName());

    private final EngineConfig config;
    private final ChainSender chain;
    private final WebhookNotifier webhook;
    private final PayoutStore store;
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private Thread worker;

    public PayoutBatcher(EngineConfig config, ChainSender chain, WebhookNotifier webhook, PayoutStore store) {
        this.config = config;
        this.chain = chain;
        this.webhook = webhook;
        this.store = store;
    }

    public AcceptResult enqueue(String idempotencyKey, List<Transfer> transfers) throws SQLException {
        String requestHash = fingerprint(transfers);
        IdempotentHit existing = store.findIdempotent(idempotencyKey);
        if (existing != null) {
            return replay(existing, requestHash);
        }
        String batchId = UUID.randomUUID().toString();
        List<QueuedTransfer> stored;
        try {
            stored = store.insertQueued(batchId, idempotencyKey, requestHash, transfers);
        } catch (DuplicateIdempotencyKey e) {
            existing = store.findIdempotent(idempotencyKey);
            if (existing == null) {
                throw e;
            }
            return replay(existing, requestHash);
        }
        for (QueuedTransfer row : stored) {
            queue.add(new Item(row.id(), row.batchId(), row.transfer()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batchId);
        body.put("status", "queued");
        body.put("count", transfers.size());
        body.put("txHash", null);
        body.put("error", null);
        return new AcceptResult(body, true);
    }

    private static AcceptResult replay(IdempotentHit existing, String requestHash) {
        if (existing.requestHash() != null && !existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflict("X-Idempotency-Key was already used with a different body");
        }
        return new AcceptResult(existing.status(), false);
    }

    private static String fingerprint(List<Transfer> transfers) {
        StringBuilder canonical = new StringBuilder();
        for (Transfer transfer : transfers) {
            canonical.append(transfer.to()).append('\n')
                    .append(transfer.amountNano()).append('\n')
                    .append(transfer.comment() == null ? "" : transfer.comment()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record AcceptResult(Map<String, Object> status, boolean created) {
    }

    public static final class IdempotencyConflict extends IllegalStateException {
        public IdempotencyConflict(String message) {
            super(message);
        }
    }

    public Map<String, Object> status(String batchId) throws SQLException {
        return store.batchStatus(batchId);
    }

    public void start() throws SQLException {
        List<QueuedTransfer> pending = store.loadQueued();
        for (QueuedTransfer row : pending) {
            queue.add(new Item(row.id(), row.batchId(), row.transfer()));
        }
        int sending = store.countSending();
        LOG.info("loaded " + pending.size() + " queued transfers from " + config.dbPath());
        if (sending > 0) {
            LOG.warning(sending + " transfers are sending from a previous run and will not be broadcast again; "
                    + "after " + config.sendingStaleMs() + " ms they are confirmed from the chain or marked failed");
        }
        running = true;
        worker = new Thread(this::loop, "payout-batcher");
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
        List<Item> buffer = new ArrayList<>();
        long flushAt = 0;
        while (running) {
            try {
                long wait = buffer.isEmpty() ? 1000 : Math.max(1, flushAt - System.currentTimeMillis());
                Item item = queue.poll(wait, TimeUnit.MILLISECONDS);
                if (item != null) {
                    if (buffer.isEmpty()) {
                        flushAt = System.currentTimeMillis() + config.batchWindowMs();
                    }
                    buffer.add(item);
                }
                boolean windowElapsed = !buffer.isEmpty() && System.currentTimeMillis() >= flushAt;
                if (buffer.size() >= config.batchMaxSize() || windowElapsed) {
                    int size = Math.min(config.batchMaxSize(), buffer.size());
                    List<Item> chunk = new ArrayList<>(buffer.subList(0, size));
                    buffer.subList(0, size).clear();
                    if (!buffer.isEmpty()) {
                        flushAt = System.currentTimeMillis() + config.batchWindowMs();
                    }
                    long pauseMs = dispatch(chunk, buffer);
                    if (pauseMs > 0) {
                        flushAt = System.currentTimeMillis() + pauseMs;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "batcher loop error", e);
            }
        }
    }

    private long dispatch(List<Item> chunk, List<Item> buffer) throws SQLException {
        List<Item> live = new ArrayList<>(chunk.size());
        for (Item item : chunk) {
            if (store.isFailed(item.batchId)) {
                continue;
            }
            live.add(item);
        }
        if (live.isEmpty()) {
            return 0;
        }
        live = withoutPoison(live, buffer);
        if (live.isEmpty()) {
            return 0;
        }

        List<Long> ids = new ArrayList<>(live.size());
        List<Transfer> transfers = new ArrayList<>(live.size());
        Map<String, List<Long>> byBatch = new LinkedHashMap<>();
        for (Item item : live) {
            ids.add(item.id);
            transfers.add(item.transfer);
            byBatch.computeIfAbsent(item.batchId, key -> new ArrayList<>()).add(item.id);
        }

        if (!chain.isReady()) {
            fail(byBatch, buffer, "wallet address or key file is not set");
            return 0;
        }
        BigInteger need = requiredNano(transfers);
        try {
            String rawBalance = chain.balanceNano();
            if (rawBalance == null || rawBalance.isBlank()) {
                return hold(live, buffer, byBatch, need, "unknown", "wallet balance is unknown");
            }
            BigInteger have = new BigInteger(rawBalance);
            if (have.compareTo(need) < 0) {
                return hold(live, buffer, byBatch, need, have.toString(),
                        "insufficient balance: need " + need + " nano, have " + have + " nano");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "balance check failed", e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return hold(live, buffer, byBatch, need, "unknown", "balance check failed: " + message);
        }

        try {
            store.markSending(ids);
            Outgoing outgoing;
            try {
                outgoing = chain.prepare(transfers);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "bus of " + transfers.size() + " transfers was not signed", e);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                fail(byBatch, buffer, message);
                return 0;
            }
            store.rememberHash(byBatch.keySet(), outgoing.hash());
            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    chain.broadcast(outgoing);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt == 3) {
                        break;
                    }
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        lastError = interrupted;
                        break;
                    }
                }
            }
            if (lastError != null) {
                LOG.log(Level.WARNING, "broadcast of " + transfers.size()
                        + " transfers failed after 3 attempts, hash " + outgoing.hash() + " was stored", lastError);
                return 0;
            }
            LOG.info("bus of " + transfers.size() + " transfers sent, hash " + outgoing.hash());
            try {
                for (Map.Entry<String, List<Long>> entry : byBatch.entrySet()) {
                    notify(store.markSent(entry.getKey(), entry.getValue(), outgoing.hash()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "hash " + outgoing.hash()
                        + " was broadcast but the sent status was not stored", e);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "bus of " + transfers.size() + " transfers failed", e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            fail(byBatch, buffer, message);
        }
        return 0;
    }

    private long hold(List<Item> live, List<Item> buffer, Map<String, List<Long>> byBatch,
            BigInteger need, String balanceNano, String message) throws SQLException {
        List<String> fresh = new ArrayList<>();
        for (String batchId : byBatch.keySet()) {
            if (store.markPaused(batchId, message)) {
                fresh.add(batchId);
                Map<String, Object> status = store.batchStatus(batchId);
                webhook.batchUpdated(batchId, "paused", (Integer) status.get("count"), null, message);
            }
        }
        if (!fresh.isEmpty()) {
            webhook.insufficientBalance(fresh, need.toString(), balanceNano, networkFeeNano(live.size()).toString());
        }
        buffer.addAll(0, live);
        return config.pauseRetryMs();
    }

    private List<Item> withoutPoison(List<Item> live, List<Item> buffer) throws SQLException {
        Map<String, List<Long>> poisoned = new LinkedHashMap<>();
        List<Item> clean = new ArrayList<>(live.size());
        for (Item item : live) {
            if (TonAddress.isValid(item.transfer.to())) {
                clean.add(item);
            } else {
                poisoned.computeIfAbsent(item.batchId, key -> new ArrayList<>()).add(item.id);
            }
        }
        if (poisoned.isEmpty()) {
            return clean;
        }
        clean.removeIf(item -> poisoned.containsKey(item.batchId));
        LOG.warning(poisoned.size() + " batches dropped before broadcast because of an invalid destination");
        fail(poisoned, buffer, "invalid destination");
        return clean;
    }

    private void fail(Map<String, List<Long>> byBatch, List<Item> buffer, String message) throws SQLException {
        for (String batchId : byBatch.keySet()) {
            buffer.removeIf(item -> batchId.equals(item.batchId));
            queue.removeIf(item -> batchId.equals(item.batchId));
            notify(store.markFailed(batchId, message));
        }
    }

    private BigInteger requiredNano(List<Transfer> transfers) {
        BigInteger sum = networkFeeNano(transfers.size());
        for (Transfer transfer : transfers) {
            sum = sum.add(new BigInteger(transfer.amountNano()));
        }
        return sum;
    }

    private BigInteger networkFeeNano(int transfers) {
        return networkFeeNano(config.feeReserveNano(), config.feePerTransferNano(), transfers);
    }

    public static BigInteger networkFeeNano(long baseNano, long perTransferNano, int transfers) {
        return BigInteger.valueOf(baseNano)
                .add(BigInteger.valueOf(perTransferNano).multiply(BigInteger.valueOf(transfers)));
    }

    private void notify(BatchOutcome outcome) {
        if (!outcome.terminal()) {
            return;
        }
        webhook.batchUpdated(outcome.batchId(), outcome.status(), outcome.count(), outcome.txHash(), outcome.error());
    }

    private static final class Item {
        private final long id;
        private final String batchId;
        private final Transfer transfer;

        private Item(long id, String batchId, Transfer transfer) {
            this.id = id;
            this.batchId = batchId;
            this.transfer = transfer;
        }
    }
}

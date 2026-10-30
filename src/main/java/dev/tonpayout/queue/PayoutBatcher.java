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
    /** Signed message kept for a network retry. A new signature would be a second payment. */
    private Outgoing pendingOutgoing;
    private List<Long> pendingIds;
    private long retryNotBefore;

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
                if (shouldFlush(buffer, windowElapsed)) {
                    List<Item> chunk = takeWholeBatches(buffer);
                    if (!buffer.isEmpty()) {
                        flushAt = System.currentTimeMillis() + config.batchWindowMs();
                    }
                    if (!chunk.isEmpty()) {
                        long pauseMs = dispatch(chunk, buffer);
                        if (pauseMs > 0) {
                            flushAt = System.currentTimeMillis() + pauseMs;
                        }
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

    /**
     * One payout request stays in one external message. A later request is not sliced
     * to fill the remaining room: that would let one half land on-chain and the other
     * be marked failed.
     */
    private boolean shouldFlush(List<Item> buffer, boolean windowElapsed) throws SQLException {
        if (buffer.isEmpty()) {
            return false;
        }
        if (pendingOutgoing != null && startsWith(buffer, pendingIds)) {
            return windowElapsed && System.currentTimeMillis() >= retryNotBefore;
        }
        if (windowElapsed) {
            return true;
        }
        int packed = 0;
        int index = 0;
        while (index < buffer.size()) {
            String batchId = buffer.get(index).batchId;
            int count = batchRun(buffer, index);
            int total = batchTotal(batchId);
            if (count < total || count > config.batchMaxSize()) {
                return count > config.batchMaxSize() && count == total;
            }
            if (packed > 0 && packed + count > config.batchMaxSize()) {
                return true;
            }
            packed += count;
            index += count;
            if (packed >= config.batchMaxSize()) {
                return true;
            }
        }
        return false;
    }

    private List<Item> takeWholeBatches(List<Item> buffer) throws SQLException {
        if (pendingOutgoing != null && startsWith(buffer, pendingIds)) {
            List<Item> chunk = new ArrayList<>(buffer.subList(0, pendingIds.size()));
            buffer.subList(0, pendingIds.size()).clear();
            return chunk;
        }
        List<Item> chunk = new ArrayList<>();
        while (!buffer.isEmpty()) {
            String batchId = buffer.get(0).batchId;
            int count = batchRun(buffer, 0);
            int total = batchTotal(batchId);
            if (count < total) {
                break;
            }
            List<Item> batch = new ArrayList<>(buffer.subList(0, count));
            if (count > config.batchMaxSize()) {
                buffer.subList(0, count).clear();
                Map<String, List<Long>> rejected = new LinkedHashMap<>();
                List<Long> ids = new ArrayList<>(batch.size());
                for (Item item : batch) {
                    ids.add(item.id);
                }
                rejected.put(batchId, ids);
                fail(rejected, buffer, "transfers count exceeds maximum highload batch size ("
                        + config.batchMaxSize() + ")");
                continue;
            }
            if (!chunk.isEmpty() && chunk.size() + count > config.batchMaxSize()) {
                break;
            }
            chunk.addAll(batch);
            buffer.subList(0, count).clear();
            if (chunk.size() >= config.batchMaxSize()) {
                break;
            }
        }
        return chunk;
    }

    private static int batchRun(List<Item> buffer, int index) {
        String batchId = buffer.get(index).batchId;
        int end = index + 1;
        while (end < buffer.size() && batchId.equals(buffer.get(end).batchId)) {
            end++;
        }
        return end - index;
    }

    private int batchTotal(String batchId) throws SQLException {
        Map<String, Object> status = store.batchStatus(batchId);
        Object raw = status == null ? null : status.get("count");
        return raw instanceof Number number ? number.intValue() : 0;
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
            Outgoing outgoing = matchingPending(live);
            if (pendingOutgoing != null && outgoing == null) {
                LOG.warning("signed boc does not match the transfers still waiting; not signing a second message");
                clearPending();
                return 0;
            }
            if (outgoing == null) {
                try {
                    outgoing = chain.prepare(transfers);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "bus of " + transfers.size() + " transfers was not signed", e);
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    fail(byBatch, buffer, message);
                    return 0;
                }
                store.rememberHash(byBatch.keySet(), outgoing.hash());
                pendingOutgoing = outgoing;
                pendingIds = List.copyOf(ids);
            }
            Exception lastError = broadcastAttempts(outgoing);
            if (lastError != null) {
                if (retryableBroadcast(lastError)) {
                    LOG.log(Level.WARNING, "broadcast of " + transfers.size()
                            + " transfers failed after 3 attempts, hash " + outgoing.hash()
                            + " will be sent again", lastError);
                    retryNotBefore = System.currentTimeMillis() + config.pauseRetryMs();
                    buffer.addAll(0, live);
                    return config.pauseRetryMs();
                }
                LOG.log(Level.WARNING, "broadcast of " + transfers.size()
                        + " transfers failed after 3 attempts, hash " + outgoing.hash() + " was stored", lastError);
                clearPending();
                return 0;
            }
            clearPending();
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

    private Outgoing matchingPending(List<Item> live) {
        if (pendingOutgoing == null || pendingIds == null || live.size() != pendingIds.size()) {
            return null;
        }
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).id != pendingIds.get(i)) {
                return null;
            }
        }
        return pendingOutgoing;
    }

    private boolean startsWith(List<Item> buffer, List<Long> ids) {
        if (ids == null || buffer.size() < ids.size()) {
            return false;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (buffer.get(i).id != ids.get(i)) {
                return false;
            }
        }
        return true;
    }

    private void clearPending() {
        pendingOutgoing = null;
        pendingIds = null;
        retryNotBefore = 0;
    }

    private Exception broadcastAttempts(Outgoing outgoing) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                chain.broadcast(outgoing);
                return null;
            } catch (Exception e) {
                lastError = e;
                if (attempt == 3) {
                    break;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return interrupted;
                }
            }
        }
        return lastError;
    }

    /** Connection loss, rate limit, and a temporary node error. The same signed bytes can be sent again. */
    private static boolean retryableBroadcast(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return false;
            }
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.NoRouteToHostException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("connection refused")
                        || lower.contains("connection reset")
                        || lower.contains("failed to connect")
                        || lower.contains("timed out")
                        || lower.contains("timeout")
                        || lower.contains("429")
                        || lower.contains("rate limit")
                        || lower.contains("too many requests")
                        || lower.contains("code=500")
                        || lower.contains("http 500")
                        || lower.contains("liteserver")
                        || lower.contains("code=502")
                        || lower.contains("code=503")
                        || lower.contains("code=504")
                        || lower.contains("http 502")
                        || lower.contains("http 503")
                        || lower.contains("http 504")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
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
            if (TonAddress.isValid(item.transfer.to(), config.tonNetwork())) {
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

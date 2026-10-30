package dev.tonpayout.queue;

import java.sql.SQLException;
import java.util.ArrayList;
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
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutStore.BatchOutcome;
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

    public String enqueue(List<Transfer> transfers) throws SQLException {
        String batchId = UUID.randomUUID().toString();
        List<QueuedTransfer> stored = store.insertQueued(batchId, transfers);
        for (QueuedTransfer row : stored) {
            queue.add(new Item(row.id(), row.batchId(), row.transfer()));
        }
        return batchId;
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
            LOG.warning(sending + " transfers are sending from a previous run and will not be broadcast again");
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
                    dispatch(chunk, buffer);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "batcher loop error", e);
            }
        }
    }

    private void dispatch(List<Item> chunk, List<Item> buffer) throws SQLException {
        List<Item> live = new ArrayList<>(chunk.size());
        for (Item item : chunk) {
            if (store.isFailed(item.batchId)) {
                continue;
            }
            live.add(item);
        }
        if (live.isEmpty()) {
            return;
        }

        List<Long> ids = new ArrayList<>(live.size());
        List<Transfer> transfers = new ArrayList<>(live.size());
        Map<String, List<Long>> byBatch = new LinkedHashMap<>();
        for (Item item : live) {
            ids.add(item.id);
            transfers.add(item.transfer);
            byBatch.computeIfAbsent(item.batchId, key -> new ArrayList<>()).add(item.id);
        }

        try {
            store.markSending(ids);
            if (!chain.isReady()) {
                throw new IllegalStateException("wallet address or key file is not set");
            }
            String txHash = chain.send(transfers);
            LOG.info("bus of " + transfers.size() + " transfers sent, hash " + txHash);
            for (Map.Entry<String, List<Long>> entry : byBatch.entrySet()) {
                notify(store.markSent(entry.getKey(), entry.getValue(), txHash));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "bus of " + transfers.size() + " transfers failed", e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            for (String batchId : byBatch.keySet()) {
                buffer.removeIf(item -> batchId.equals(item.batchId));
                queue.removeIf(item -> batchId.equals(item.batchId));
                notify(store.markFailed(batchId, message));
            }
        }
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

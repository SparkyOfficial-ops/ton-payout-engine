package dev.tonpayout.queue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.chain.ChainSender;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.notify.WebhookNotifier;

public final class PayoutBatcher {
    private static final Logger LOG = Logger.getLogger(PayoutBatcher.class.getName());

    private final EngineConfig config;
    private final ChainSender chain;
    private final WebhookNotifier webhook;
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Batch> batches = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread worker;

    public PayoutBatcher(EngineConfig config, ChainSender chain, WebhookNotifier webhook) {
        this.config = config;
        this.chain = chain;
        this.webhook = webhook;
    }

    public String enqueue(List<Transfer> transfers) {
        String batchId = UUID.randomUUID().toString();
        batches.put(batchId, new Batch(batchId, transfers.size()));
        for (Transfer transfer : transfers) {
            queue.add(new Item(batchId, transfer));
        }
        return batchId;
    }

    public Map<String, Object> status(String batchId) {
        Batch batch = batches.get(batchId);
        if (batch == null) {
            return null;
        }
        synchronized (batch) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("batchId", batch.id);
            body.put("status", batch.status);
            body.put("count", batch.total);
            body.put("txHash", batch.txHash);
            body.put("error", batch.error);
            return body;
        }
    }

    public void start() {
        running = true;
        worker = new Thread(this::loop, "payout-batcher");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
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
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "batcher loop error", e);
            }
        }
    }

    private void dispatch(List<Item> chunk, List<Item> buffer) {
        List<Item> live = new ArrayList<>(chunk.size());
        for (Item item : chunk) {
            Batch batch = batches.get(item.batchId);
            if (batch == null) {
                continue;
            }
            synchronized (batch) {
                if ("failed".equals(batch.status)) {
                    continue;
                }
            }
            live.add(item);
        }
        if (live.isEmpty()) {
            return;
        }

        List<Transfer> transfers = new ArrayList<>(live.size());
        for (Item item : live) {
            transfers.add(item.transfer);
        }
        String txHash = null;
        String status;
        String error = null;
        try {
            if (!chain.isReady()) {
                throw new IllegalStateException("wallet address or key file is not set");
            }
            txHash = chain.send(transfers);
            status = "sent";
            LOG.info("bus of " + transfers.size() + " transfers sent, hash " + txHash);
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage();
            LOG.log(Level.WARNING, "bus of " + transfers.size() + " transfers failed", e);
        }

        Map<String, Integer> counts = new HashMap<>();
        for (Item item : live) {
            counts.merge(item.batchId, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            finish(entry.getKey(), entry.getValue(), status, txHash, error);
            if ("failed".equals(status)) {
                String batchId = entry.getKey();
                buffer.removeIf(item -> batchId.equals(item.batchId));
                queue.removeIf(item -> batchId.equals(item.batchId));
            }
        }
    }

    private void finish(String batchId, int done, String status, String txHash, String error) {
        Batch batch = batches.get(batchId);
        boolean terminal = false;
        String notifyStatus = status;
        String notifyError = error;
        String notifyTx = txHash;
        synchronized (batch) {
            boolean alreadyDone = "sent".equals(batch.status) || "failed".equals(batch.status);
            if ("failed".equals(status)) {
                batch.status = "failed";
                batch.error = error;
                batch.pending = 0;
                terminal = !alreadyDone;
            } else {
                batch.pending -= done;
                if (txHash != null && !txHash.isBlank()) {
                    batch.txHash = batch.txHash == null ? txHash : batch.txHash + "," + txHash;
                }
                if (batch.pending > 0) {
                    batch.status = "sending";
                } else if (!"failed".equals(batch.status)) {
                    batch.pending = 0;
                    batch.status = status;
                    terminal = true;
                }
            }
            notifyStatus = batch.status;
            notifyError = batch.error;
            notifyTx = batch.txHash;
        }
        if (terminal) {
            webhook.batchUpdated(batchId, notifyStatus, batch.total, notifyTx, notifyError);
        }
    }

    private static final class Item {
        private final String batchId;
        private final Transfer transfer;

        private Item(String batchId, Transfer transfer) {
            this.batchId = batchId;
            this.transfer = transfer;
        }
    }

    private static final class Batch {
        private final String id;
        private final int total;
        private int pending;
        private String status = "queued";
        private String txHash;
        private String error;

        private Batch(String id, int total) {
            this.id = id;
            this.total = total;
            this.pending = total;
        }
    }
}

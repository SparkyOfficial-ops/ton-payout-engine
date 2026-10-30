package dev.tonpayout.queue;

import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.config.EngineConfig;

/**
 * Moves confirmed and failed batches older than the retention window
 * out of the hot database. Queued, paused, sending and sent rows stay.
 */
public final class Housekeeping {
    private static final Logger LOG = Logger.getLogger(Housekeeping.class.getName());
    private static final int CHUNK = 200;

    private final EngineConfig config;
    private final PayoutStore store;
    private volatile boolean running;
    private Thread worker;

    public Housekeeping(EngineConfig config, PayoutStore store) {
        this.config = config;
        this.store = store;
    }

    public void start() {
        running = true;
        worker = new Thread(this::loop, "housekeeping");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int sweep() throws Exception {
        long updatedBefore = System.currentTimeMillis() - config.housekeepingRetentionMs();
        int total = 0;
        int moved;
        do {
            moved = store.archiveTerminal(updatedBefore, CHUNK);
            total += moved;
        } while (moved > 0 && !Thread.currentThread().isInterrupted());
        if (total > 0) {
            try {
                store.reclaim();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "incremental vacuum failed", e);
            }
            LOG.info("archived " + total + " confirmed or failed batches");
        }
        return total;
    }

    private void loop() {
        while (running) {
            try {
                sweep();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "housekeeping failed", e);
            }
            if (!running || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                Thread.sleep(config.housekeepingIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

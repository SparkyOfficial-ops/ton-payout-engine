package dev.tonpayout.notify;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.log.BatchMdc;
import dev.tonpayout.queue.PayoutStore;
import dev.tonpayout.queue.PayoutStore.WebhookEvent;

/**
 * Delivers {@code confirmed} and {@code failed} payloads stored in {@code webhook_events}.
 * A crash after the client answers and before {@code delivered} sends the same payload again.
 */
public final class WebhookOutbox {
    private static final Logger LOG = Logger.getLogger(WebhookOutbox.class.getName());
    private static final int BATCH = 20;
    private static final long IDLE_MS = 1_000;

    private final PayoutStore store;
    private final WebhookNotifier notifier;
    private volatile boolean running;
    private Thread worker;

    public WebhookOutbox(PayoutStore store, WebhookNotifier notifier) {
        this.store = store;
        this.notifier = notifier;
    }

    public void start() {
        running = true;
        worker = new Thread(this::loop, "webhook-outbox");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(notifier.timeoutMs() + 2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    int dispatch() throws Exception {
        List<WebhookEvent> due = store.claimDueWebhooks(System.currentTimeMillis(), BATCH);
        for (WebhookEvent event : due) {
            if (!running && worker != null) {
                break;
            }
            BatchMdc.set(event.batchId());
            try {
                if (notifier.deliver(event.payload())) {
                    store.markWebhookDelivered(event.id());
                    LOG.info("webhook delivered for batch " + event.batchId() + " attempt " + event.attempts());
                } else {
                    LOG.warning("webhook not delivered for batch " + event.batchId()
                            + " attempt " + event.attempts()
                            + ", next try in " + WebhookRetry.delayMs(event.attempts()) + " ms");
                }
            } finally {
                BatchMdc.clear();
            }
        }
        return due.size();
    }

    private void loop() {
        while (running) {
            try {
                int delivered = dispatch();
                if (!running) {
                    return;
                }
                if (delivered == 0) {
                    Thread.sleep(IDLE_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "webhook outbox failed", e);
                try {
                    Thread.sleep(IDLE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

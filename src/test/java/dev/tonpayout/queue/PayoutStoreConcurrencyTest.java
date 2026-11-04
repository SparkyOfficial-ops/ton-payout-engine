package dev.tonpayout.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.tonpayout.queue.PayoutStore.IdempotentHit;

class PayoutStoreConcurrencyTest {
    @Test
    void statusAndHealthDoNotWaitForTheWriteLock() throws Exception {
        Path db = Path.of("target/junit-store-read.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            store.insertQueued("batch-1", "key-1", "hash-1", List.of(transfer()));
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                synchronized (store) {
                    holding.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            writer.start();
            assertTrue(holding.await(2, TimeUnit.SECONDS));
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                long started = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    futures.add(pool.submit(() -> {
                        Map<String, Object> status = store.batchStatus("batch-1");
                        if (status == null || !"queued".equals(status.get("status"))) {
                            throw new IllegalStateException("status " + status);
                        }
                        IdempotentHit hit = store.findIdempotent("key-1");
                        if (hit == null || !"hash-1".equals(hit.requestHash())) {
                            throw new IllegalStateException("idempotency " + hit);
                        }
                        store.ping();
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(2, TimeUnit.SECONDS);
                }
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                assertTrue(elapsedMs < 1_500L, "reads waited " + elapsedMs + " ms on the write lock");
            } finally {
                release.countDown();
                writer.join(2_000);
                pool.shutdownNow();
            }
            store.markFailed("batch-1", "done");
            assertEquals(1, store.archiveTerminal(System.currentTimeMillis() + 1_000, 10));
            Map<String, Object> archived = store.batchStatus("batch-1");
            assertNotNull(archived);
            assertEquals("failed", archived.get("status"));
            assertNotNull(store.findIdempotent("key-1"));
        } finally {
            deleteDb(db);
        }
    }

    private static Transfer transfer() {
        return new Transfer("0:" + "ab".repeat(32), "1", "", false);
    }

    private static void deleteDb(Path db) throws Exception {
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db.toString() + "-wal"));
        Files.deleteIfExists(Path.of(db.toString() + "-shm"));
        Path archive = PayoutStore.archivePath(db);
        Files.deleteIfExists(archive);
        Files.deleteIfExists(Path.of(archive.toString() + "-wal"));
        Files.deleteIfExists(Path.of(archive.toString() + "-shm"));
    }
}

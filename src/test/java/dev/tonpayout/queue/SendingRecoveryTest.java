package dev.tonpayout.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.tonpayout.chain.BlockInclusion;
import dev.tonpayout.chain.ChainLook;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.queue.PayoutStore.BatchOutcome;
import dev.tonpayout.queue.PayoutStore.QueuedTransfer;

class SendingRecoveryTest {
    private static final String HASH = "ab".repeat(32);

    @Test
    void startupConfirmsAFreshSendingBatch() throws Exception {
        Path db = Path.of("target/junit-recovery-fresh.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            sending(store, "batch-fresh");
            SendingRecovery recovery = new SendingRecovery(config(), store, hashes -> true);
            List<BatchOutcome> resolved = recovery.sweep();
            assertEquals(1, resolved.size());
            assertEquals("confirmed", resolved.get(0).status());
            assertEquals("confirmed", store.batchStatus("batch-fresh").get("status"));
        } finally {
            deleteDb(db);
        }
    }

    @Test
    void startupKeepsCheckingUntilTheHashIsInABlock() throws Exception {
        Path db = Path.of("target/junit-recovery-later.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            sending(store, "batch-later");
            AtomicBoolean included = new AtomicBoolean(false);
            BlockInclusion inclusion = hashes -> included.get();
            SendingRecovery recovery = new SendingRecovery(config(), store, inclusion);
            assertTrue(recovery.sweep().isEmpty());
            assertEquals("sending", store.batchStatus("batch-later").get("status"));
            included.set(true);
            Thread.sleep(250);
            List<BatchOutcome> resolved = recovery.sweep(new ChainLook(inclusion));
            assertEquals(1, resolved.size());
            assertEquals("confirmed", resolved.get(0).status());
        } finally {
            deleteDb(db);
        }
    }

    @Test
    void liveSendIsLeftAloneUntilItIsStale() throws Exception {
        Path db = Path.of("target/junit-recovery-live.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            SendingRecovery recovery = new SendingRecovery(config(), store, hashes -> true);
            assertTrue(recovery.sweep().isEmpty());
            sending(store, "batch-live");
            assertTrue(recovery.sweep(new ChainLook(hashes -> true)).isEmpty());
            assertEquals("sending", store.batchStatus("batch-live").get("status"));
        } finally {
            deleteDb(db);
        }
    }

    @Test
    void pollerStartSweepsBeforeTheLoop() throws Exception {
        Path db = Path.of("target/junit-recovery-poller.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            sending(store, "batch-poller");
            BlockInclusion inclusion = hashes -> true;
            TxPoller poller = new TxPoller(config(), store, inclusion, new SendingRecovery(config(), store, inclusion));
            poller.start();
            try {
                assertEquals("confirmed", store.batchStatus("batch-poller").get("status"));
            } finally {
                poller.stop();
            }
        } finally {
            deleteDb(db);
        }
    }

    private static void sending(PayoutStore store, String batchId) throws Exception {
        List<QueuedTransfer> rows = store.insertQueued(batchId, null, null, List.of(
                new Transfer("0:" + "cd".repeat(32), "1", "", false)));
        store.markSending(List.of(rows.get(0).id()));
        store.rememberHash(List.of(batchId), HASH);
    }

    private static EngineConfig config() {
        Properties properties = new Properties();
        properties.setProperty("sending.staleMs", "300000");
        properties.setProperty("confirm.delayMs", "200");
        properties.setProperty("message.ttlMs", "960000");
        return EngineConfig.from(properties);
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

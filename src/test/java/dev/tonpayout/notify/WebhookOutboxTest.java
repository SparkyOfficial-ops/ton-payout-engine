package dev.tonpayout.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.queue.PayoutStore;
import dev.tonpayout.queue.PayoutStore.QueuedTransfer;
import dev.tonpayout.queue.Transfer;

class WebhookOutboxTest {
    @Test
    void retriesWaitFiveSecondsThenThirtyThenTwoMinutesThenTen() {
        assertEquals(5_000L, WebhookRetry.delayMs(1));
        assertEquals(30_000L, WebhookRetry.delayMs(2));
        assertEquals(120_000L, WebhookRetry.delayMs(3));
        assertEquals(600_000L, WebhookRetry.delayMs(4));
        assertEquals(600_000L, WebhookRetry.delayMs(9));
    }

    @Test
    void failedStatusIsDeliveredAfterTheClientRecovers() throws Exception {
        Path db = Path.of("target/junit-webhook.db");
        deleteDb(db);
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int code = hits.incrementAndGet() == 1 ? 500 : 200;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        try (PayoutStore store = new PayoutStore(db)) {
            store.recordWebhooks(true);
            store.insertQueued("batch-fail", null, null, List.of(transfer()));
            store.markFailed("batch-fail", "invalid destination");
            store.markFailed("batch-fail", "invalid destination");
            assertEquals(1, count(db, "pending"));
            assertTrue(payload(db).contains("\"status\":\"failed\""));
            assertTrue(payload(db).contains("\"batchId\":\"batch-fail\""));

            Properties properties = new Properties();
            properties.setProperty("webhook.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            properties.setProperty("webhook.timeoutMs", "2000");
            WebhookOutbox outbox = new WebhookOutbox(store, new WebhookNotifier(EngineConfig.from(properties)));
            long before = System.currentTimeMillis();
            assertEquals(1, outbox.dispatch());
            long after = System.currentTimeMillis();
            assertEquals(1, count(db, "pending"));
            assertEquals(0, outbox.dispatch());
            long next = nextRetryAt(db);
            assertTrue(next >= before + 5_000L && next <= after + 5_000L);

            dueNow(db);
            assertEquals(1, outbox.dispatch());
            assertEquals(0, count(db, "pending"));
            assertEquals(1, count(db, "delivered"));
            assertEquals(2, hits.get());
        } finally {
            server.stop(0);
            deleteDb(db);
        }
    }

    @Test
    void sentIsNotQueuedAndConfirmedIs() throws Exception {
        Path db = Path.of("target/junit-webhook-confirmed.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            store.recordWebhooks(true);
            List<QueuedTransfer> rows = store.insertQueued("batch-ok", null, null, List.of(transfer()));
            List<Long> ids = List.of(rows.get(0).id());
            store.markSending(ids);
            store.markSent("batch-ok", ids, "ab".repeat(32));
            assertEquals(0, count(db, "pending"));
            store.markConfirmed("batch-ok");
            assertEquals(1, count(db, "pending"));
            assertTrue(payload(db).contains("\"status\":\"confirmed\""));
        } finally {
            deleteDb(db);
        }
    }

    @Test
    void archiveDropsDeliveredEventsAndKeepsPendingOnes() throws Exception {
        Path db = Path.of("target/junit-webhook-archive.db");
        deleteDb(db);
        try (PayoutStore store = new PayoutStore(db)) {
            store.recordWebhooks(true);
            store.insertQueued("batch-done", null, null, List.of(transfer()));
            store.markFailed("batch-done", "done");
            store.insertQueued("batch-wait", null, null, List.of(transfer()));
            store.markFailed("batch-wait", "wait");
            long deliveredId = eventId(db, "batch-done");
            store.markWebhookDelivered(deliveredId);
            int archived = store.archiveTerminal(System.currentTimeMillis() + 1_000, 10);
            assertEquals(2, archived);
            assertEquals(0, countFor(db, "batch-done"));
            assertEquals(1, countFor(db, "batch-wait"));
            assertEquals("pending", statusFor(db, "batch-wait"));
        } finally {
            deleteDb(db);
        }
    }

    private static Transfer transfer() {
        return new Transfer("0:" + "ab".repeat(32), "1", "", false);
    }

    private static void dueNow(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            try (java.sql.Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA busy_timeout=5000");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE webhook_events SET next_retry_at = 0 WHERE status = 'pending'")) {
                statement.executeUpdate();
            }
        }
    }

    private static int count(Path db, String status) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM webhook_events WHERE status = ?")) {
            statement.setString(1, status);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static int countFor(Path db, String batchId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM webhook_events WHERE batch_id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static String statusFor(Path db, String batchId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT status FROM webhook_events WHERE batch_id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    private static long eventId(Path db, String batchId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM webhook_events WHERE batch_id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static String payload(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT payload FROM webhook_events ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static long nextRetryAt(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT next_retry_at FROM webhook_events WHERE status = 'pending'")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
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

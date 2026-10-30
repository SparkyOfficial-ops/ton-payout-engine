package dev.tonpayout.queue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PayoutStore implements AutoCloseable {
    private static final int BUSY_TIMEOUT_MS = 5_000;
    private static final int PING_TIMEOUT_MS = 500;

    private final Connection connection;

    public PayoutStore(Path database) throws SQLException {
        try {
            Path parent = database.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create database directory", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA auto_vacuum=INCREMENTAL");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS batches (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        total INTEGER NOT NULL,
                        tx_hash TEXT,
                        error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transfers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        batch_id TEXT NOT NULL REFERENCES batches(id),
                        destination TEXT NOT NULL,
                        amount_nano TEXT NOT NULL,
                        comment TEXT,
                        status TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_batch ON transfers(batch_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_status ON transfers(status)");
        }
        addColumn("confirm_checks", "INTEGER NOT NULL DEFAULT 0");
        addColumn("idempotency_key", "TEXT");
        addColumn("request_hash", "TEXT");
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_batches_idempotency ON batches(idempotency_key)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_batches_updated ON batches(updated_at)");
        }
        attachArchive(database);
    }

    public synchronized List<QueuedTransfer> insertQueued(String batchId, String idempotencyKey,
            String requestHash, List<Transfer> transfers) throws SQLException {
        if (idempotencyKey != null && !idempotencyKey.isBlank() && archivedKey(idempotencyKey)) {
            throw new DuplicateIdempotencyKey();
        }
        long now = System.currentTimeMillis();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement batch = connection.prepareStatement(
                    "INSERT INTO batches(id, status, total, tx_hash, error, created_at, updated_at, idempotency_key, request_hash) "
                            + "VALUES (?, 'queued', ?, NULL, NULL, ?, ?, ?, ?)")) {
                batch.setString(1, batchId);
                batch.setInt(2, transfers.size());
                batch.setLong(3, now);
                batch.setLong(4, now);
                batch.setString(5, idempotencyKey);
                batch.setString(6, requestHash);
                batch.executeUpdate();
            }
            List<QueuedTransfer> stored = new ArrayList<>(transfers.size());
            try (PreparedStatement row = connection.prepareStatement(
                    "INSERT INTO transfers(batch_id, destination, amount_nano, comment, status) VALUES (?, ?, ?, ?, 'queued')",
                    Statement.RETURN_GENERATED_KEYS)) {
                for (Transfer transfer : transfers) {
                    row.setString(1, batchId);
                    row.setString(2, transfer.to());
                    row.setString(3, transfer.amountNano());
                    row.setString(4, transfer.comment());
                    row.executeUpdate();
                    try (ResultSet keys = row.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("transfer id was not generated");
                        }
                        stored.add(new QueuedTransfer(keys.getLong(1), batchId, transfer));
                    }
                }
            }
            connection.commit();
            return stored;
        } catch (SQLException e) {
            connection.rollback();
            if (isIdempotencyConstraint(e)) {
                throw new DuplicateIdempotencyKey();
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized IdempotentHit findIdempotent(String idempotencyKey) throws SQLException {
        IdempotentHit live = readIdempotent("batches", idempotencyKey);
        return live != null ? live : readIdempotent("archive.batches", idempotencyKey);
    }

    private static boolean isIdempotencyConstraint(SQLException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return error.getErrorCode() == 19 && message.contains("idempotency");
    }

    public synchronized List<QueuedTransfer> loadQueued() throws SQLException {
        String sql = """
                SELECT t.id, t.batch_id, t.destination, t.amount_nano, t.comment
                FROM transfers t
                JOIN batches b ON b.id = t.batch_id
                WHERE t.status = 'queued' AND b.status IN ('queued', 'sending', 'paused')
                ORDER BY t.id
                """;
        List<QueuedTransfer> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(new QueuedTransfer(
                        result.getLong(1),
                        result.getString(2),
                        new Transfer(result.getString(3), result.getString(4), result.getString(5))));
            }
        }
        return rows;
    }

    public synchronized int countSending() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM transfers WHERE status = 'sending'")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public synchronized Map<String, Object> batchStatus(String batchId) throws SQLException {
        Map<String, Object> live = readBatch("batches", batchId);
        return live != null ? live : readBatch("archive.batches", batchId);
    }

    public synchronized boolean isFailed(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "failed".equals(result.getString(1));
            }
        }
    }

    /**
     * @return true when the batch moves into pause, false when it was already paused or is finished
     */
    public synchronized boolean markPaused(String batchId, String error) throws SQLException {
        String status;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                status = result.getString(1);
            }
        }
        if ("failed".equals(status) || "sent".equals(status) || "confirmed".equals(status)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE batches SET status = 'paused', error = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, error);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, batchId);
            statement.executeUpdate();
        }
        return !"paused".equals(status);
    }

    public synchronized void markSending(List<Long> transferIds) throws SQLException {
        if (transferIds.isEmpty()) {
            return;
        }
        connection.setAutoCommit(false);
        try {
            updateTransferStatus(transferIds, "sending");
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE batches SET status = 'sending', updated_at = ? "
                            + "WHERE status = 'queued' AND id IN (SELECT batch_id FROM transfers WHERE id IN ("
                            + placeholders(transferIds.size()) + "))")) {
                statement.setLong(1, System.currentTimeMillis());
                bindIds(statement, 2, transferIds);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized BatchOutcome markSent(String batchId, List<Long> transferIds, String txHash) throws SQLException {
        connection.setAutoCommit(false);
        try {
            updateTransferStatus(transferIds, "sent");
            String merged = txHash;
            String existing = selectHash(batchId);
            if (existing != null && !existing.isBlank() && txHash != null && !txHash.isBlank()) {
                merged = existing + "," + txHash;
            } else if (txHash == null || txHash.isBlank()) {
                merged = existing;
            }
            int open = countOpen(batchId);
            String status = open == 0 ? "sent" : "sending";
            String sql = open == 0
                    ? "UPDATE batches SET status = ?, tx_hash = ?, updated_at = ?, confirm_checks = 0 WHERE id = ?"
                    : "UPDATE batches SET status = ?, tx_hash = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, merged);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, batchId);
                statement.executeUpdate();
            }
            connection.commit();
            return outcome(batchId, open == 0);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized BatchOutcome markFailed(String batchId, String error) throws SQLException {
        connection.setAutoCommit(false);
        try {
            boolean alreadyFailed = isFailedInTransaction(batchId);
            try (PreparedStatement transfers = connection.prepareStatement(
                    "UPDATE transfers SET status = 'failed' WHERE batch_id = ? AND status IN ('queued', 'sending')")) {
                transfers.setString(1, batchId);
                transfers.executeUpdate();
            }
            try (PreparedStatement batch = connection.prepareStatement(
                    "UPDATE batches SET status = 'failed', error = ?, updated_at = ? WHERE id = ?")) {
                batch.setString(1, error);
                batch.setLong(2, System.currentTimeMillis());
                batch.setString(3, batchId);
                batch.executeUpdate();
            }
            connection.commit();
            return outcome(batchId, !alreadyFailed);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized List<SentBatch> sentBefore(long updatedAt) throws SQLException {
        List<SentBatch> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, total, tx_hash, confirm_checks FROM batches WHERE status = 'sent' AND updated_at <= ?")) {
            statement.setLong(1, updatedAt);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new SentBatch(
                            result.getString(1),
                            result.getInt(2),
                            result.getString(3),
                            result.getInt(4)));
                }
            }
        }
        return rows;
    }

    public synchronized List<StuckSending> staleSending(long updatedBefore) throws SQLException {
        List<StuckSending> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, total, tx_hash FROM batches WHERE status = 'sending' AND updated_at <= ?")) {
            statement.setLong(1, updatedBefore);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String batchId = result.getString(1);
                    int[] counts = transferCounts(batchId);
                    if (counts[0] == 0 || counts[2] > 0) {
                        continue;
                    }
                    rows.add(new StuckSending(batchId, result.getInt(2), result.getString(3), counts[0], counts[1]));
                }
            }
        }
        return rows;
    }

    public synchronized BatchOutcome recoverConfirmed(String batchId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int changed;
            try (PreparedStatement batch = connection.prepareStatement(
                    "UPDATE batches SET status = 'confirmed', updated_at = ? WHERE id = ? AND status = 'sending'")) {
                batch.setLong(1, System.currentTimeMillis());
                batch.setString(2, batchId);
                changed = batch.executeUpdate();
            }
            try (PreparedStatement transfers = connection.prepareStatement(
                    "UPDATE transfers SET status = 'confirmed' WHERE batch_id = ? AND status = 'sending'")) {
                transfers.setString(1, batchId);
                transfers.executeUpdate();
            }
            connection.commit();
            return outcome(batchId, changed > 0);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized BatchOutcome recoverFailed(String batchId, String error) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int changed;
            try (PreparedStatement batch = connection.prepareStatement(
                    "UPDATE batches SET status = 'failed', error = ?, updated_at = ? WHERE id = ? AND status = 'sending'")) {
                batch.setString(1, error);
                batch.setLong(2, System.currentTimeMillis());
                batch.setString(3, batchId);
                changed = batch.executeUpdate();
            }
            try (PreparedStatement transfers = connection.prepareStatement(
                    "UPDATE transfers SET status = 'failed' WHERE batch_id = ? AND status = 'sending'")) {
                transfers.setString(1, batchId);
                transfers.executeUpdate();
            }
            connection.commit();
            return outcome(batchId, changed > 0);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int[] transferCounts(String batchId) throws SQLException {
        int sending = 0;
        int sent = 0;
        int queued = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, COUNT(*) FROM transfers WHERE batch_id = ? GROUP BY status")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String status = result.getString(1);
                    int count = result.getInt(2);
                    if ("sending".equals(status)) {
                        sending = count;
                    } else if ("sent".equals(status)) {
                        sent = count;
                    } else if ("queued".equals(status)) {
                        queued = count;
                    }
                }
            }
        }
        return new int[] {sending, sent, queued};
    }

    public synchronized BatchOutcome markConfirmed(String batchId) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int changed;
            try (PreparedStatement batch = connection.prepareStatement(
                    "UPDATE batches SET status = 'confirmed', updated_at = ? WHERE id = ? AND status = 'sent'")) {
                batch.setLong(1, System.currentTimeMillis());
                batch.setString(2, batchId);
                changed = batch.executeUpdate();
            }
            try (PreparedStatement transfers = connection.prepareStatement(
                    "UPDATE transfers SET status = 'confirmed' WHERE batch_id = ? AND status = 'sent'")) {
                transfers.setString(1, batchId);
                transfers.executeUpdate();
            }
            connection.commit();
            return outcome(batchId, changed > 0);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void delayConfirmation(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE batches SET updated_at = ? WHERE id = ? AND status = 'sent'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, batchId);
            statement.executeUpdate();
        }
    }

    public synchronized int noteConfirmationMiss(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE batches SET confirm_checks = confirm_checks + 1, updated_at = ? WHERE id = ? AND status = 'sent'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, batchId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT confirm_checks FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public synchronized BatchOutcome markNotInBlock(String batchId, String error) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int changed;
            try (PreparedStatement batch = connection.prepareStatement(
                    "UPDATE batches SET status = 'failed', error = ?, updated_at = ? WHERE id = ? AND status = 'sent'")) {
                batch.setString(1, error);
                batch.setLong(2, System.currentTimeMillis());
                batch.setString(3, batchId);
                changed = batch.executeUpdate();
            }
            try (PreparedStatement transfers = connection.prepareStatement(
                    "UPDATE transfers SET status = 'failed' WHERE batch_id = ? AND status = 'sent'")) {
                transfers.setString(1, batchId);
                transfers.executeUpdate();
            }
            connection.commit();
            return outcome(batchId, changed > 0);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    public synchronized void ping() throws SQLException {
        SQLException failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + PING_TIMEOUT_MS);
            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                if (!result.next() || result.getInt(1) != 1) {
                    failure = new SQLException("database ping failed");
                }
            }
        } catch (SQLException e) {
            failure = e;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
        } catch (SQLException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public synchronized int archiveTerminal(long updatedBefore, int limit) throws SQLException {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("archive limit out of range");
        }
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM batches WHERE status IN ('confirmed', 'failed') AND updated_at <= ? "
                        + "ORDER BY updated_at LIMIT ?")) {
            statement.setLong(1, updatedBefore);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getString(1));
                }
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String in = placeholders(ids.size());
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement batches = connection.prepareStatement(
                    "INSERT INTO archive.batches(id, status, total, tx_hash, error, created_at, updated_at, "
                            + "confirm_checks, idempotency_key, request_hash, archived_at) "
                            + "SELECT id, status, total, tx_hash, error, created_at, updated_at, confirm_checks, "
                            + "idempotency_key, request_hash, ? FROM batches "
                            + "WHERE updated_at <= ? AND status IN ('confirmed', 'failed') AND id IN (" + in + ")")) {
                batches.setLong(1, now);
                batches.setLong(2, updatedBefore);
                bindStrings(batches, 3, ids);
                batches.executeUpdate();
            }
            try (PreparedStatement transfers = connection.prepareStatement(
                    "INSERT INTO archive.transfers(id, batch_id, destination, amount_nano, comment, status, archived_at) "
                            + "SELECT t.id, t.batch_id, t.destination, t.amount_nano, t.comment, t.status, ? "
                            + "FROM transfers t WHERE t.batch_id IN ("
                            + "SELECT id FROM archive.batches WHERE archived_at = ? AND id IN (" + in + "))")) {
                transfers.setLong(1, now);
                transfers.setLong(2, now);
                bindStrings(transfers, 3, ids);
                transfers.executeUpdate();
            }
            try (PreparedStatement deleteTransfers = connection.prepareStatement(
                    "DELETE FROM transfers WHERE batch_id IN ("
                            + "SELECT id FROM archive.batches WHERE archived_at = ? AND id IN (" + in + "))")) {
                deleteTransfers.setLong(1, now);
                bindStrings(deleteTransfers, 2, ids);
                deleteTransfers.executeUpdate();
            }
            int deleted;
            try (PreparedStatement deleteBatches = connection.prepareStatement(
                    "DELETE FROM batches WHERE updated_at <= ? AND status IN ('confirmed', 'failed') AND id IN ("
                            + "SELECT id FROM archive.batches WHERE archived_at = ? AND id IN (" + in + "))")) {
                deleteBatches.setLong(1, updatedBefore);
                deleteBatches.setLong(2, now);
                bindStrings(deleteBatches, 3, ids);
                deleted = deleteBatches.executeUpdate();
            }
            connection.commit();
            return deleted;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized void reclaim() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA incremental_vacuum(128)");
        }
    }

    public static Path archivePath(Path database) {
        Path absolute = database.toAbsolutePath();
        String name = absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String archiveName = dot > 0
                ? name.substring(0, dot) + "-archive" + name.substring(dot)
                : name + "-archive";
        Path parent = absolute.getParent();
        return parent == null ? Path.of(archiveName) : parent.resolve(archiveName);
    }

    private BatchOutcome outcome(String batchId, boolean terminal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, total, tx_hash, error FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("batch not found: " + batchId);
                }
                return new BatchOutcome(
                        batchId,
                        result.getString(1),
                        result.getInt(2),
                        result.getString(3),
                        result.getString(4),
                        terminal);
            }
        }
    }

    private boolean isFailedInTransaction(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "failed".equals(result.getString(1));
            }
        }
    }

    private String selectHash(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tx_hash FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private int countOpen(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM transfers WHERE batch_id = ? AND status IN ('queued', 'sending')")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void updateTransferStatus(List<Long> transferIds, String status) throws SQLException {
        if (transferIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transfers SET status = ? WHERE id IN (" + placeholders(transferIds.size()) + ")")) {
            statement.setString(1, status);
            bindIds(statement, 2, transferIds);
            statement.executeUpdate();
        }
    }

    private static String placeholders(int count) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        return sql.toString();
    }

    private static void bindIds(PreparedStatement statement, int firstIndex, List<Long> transferIds) throws SQLException {
        for (int i = 0; i < transferIds.size(); i++) {
            statement.setLong(firstIndex + i, transferIds.get(i));
        }
    }

    private static void bindStrings(PreparedStatement statement, int firstIndex, List<String> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            statement.setString(firstIndex + i, values.get(i));
        }
    }

    private void attachArchive(Path database) throws SQLException {
        String sqlPath = archivePath(database).toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("ATTACH DATABASE '" + sqlPath + "' AS archive");
            statement.execute("PRAGMA archive.journal_mode=WAL");
            statement.execute("PRAGMA archive.synchronous=FULL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS archive.batches (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        total INTEGER NOT NULL,
                        tx_hash TEXT,
                        error TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        confirm_checks INTEGER NOT NULL,
                        idempotency_key TEXT,
                        request_hash TEXT,
                        archived_at INTEGER NOT NULL
                    )
                    """);
            statement.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS archive.idx_archive_idempotency ON batches(idempotency_key)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS archive.transfers (
                        id INTEGER PRIMARY KEY,
                        batch_id TEXT NOT NULL,
                        destination TEXT NOT NULL,
                        amount_nano TEXT NOT NULL,
                        comment TEXT,
                        status TEXT NOT NULL,
                        archived_at INTEGER NOT NULL
                    )
                    """);
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS archive.idx_archive_transfers_batch ON transfers(batch_id)");
        }
    }

    private boolean archivedKey(String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM archive.batches WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private IdempotentHit readIdempotent(String table, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, request_hash, status, total, tx_hash, error FROM " + table
                        + " WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("batchId", result.getString(1));
                body.put("status", result.getString(3));
                body.put("count", result.getInt(4));
                body.put("txHash", result.getString(5));
                body.put("error", result.getString(6));
                return new IdempotentHit(body, result.getString(2));
            }
        }
    }

    private Map<String, Object> readBatch(String table, String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, total, tx_hash, error FROM " + table + " WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("batchId", batchId);
                body.put("status", result.getString(1));
                body.put("count", result.getInt(2));
                body.put("txHash", result.getString(3));
                body.put("error", result.getString(4));
                return body;
            }
        }
    }

    private void addColumn(String name, String definition) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE batches ADD COLUMN " + name + " " + definition);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!message.contains("duplicate column")) {
                throw e;
            }
        }
    }

    public record QueuedTransfer(long id, String batchId, Transfer transfer) {
    }

    public record BatchOutcome(String batchId, String status, int count, String txHash, String error, boolean terminal) {
    }

    public record SentBatch(String batchId, int count, String txHash, int checks) {
    }

    public record StuckSending(String batchId, int count, String txHash, int sending, int sent) {
    }

    public record IdempotentHit(Map<String, Object> status, String requestHash) {
    }

    public static final class DuplicateIdempotencyKey extends SQLException {
        public DuplicateIdempotencyKey() {
            super("idempotency key already exists");
        }
    }
}

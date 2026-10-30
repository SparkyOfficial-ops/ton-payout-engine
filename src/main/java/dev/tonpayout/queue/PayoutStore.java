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
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
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
    }

    public synchronized List<QueuedTransfer> insertQueued(String batchId, List<Transfer> transfers) throws SQLException {
        long now = System.currentTimeMillis();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement batch = connection.prepareStatement(
                    "INSERT INTO batches(id, status, total, created_at, updated_at) VALUES (?, 'queued', ?, ?, ?)")) {
                batch.setString(1, batchId);
                batch.setInt(2, transfers.size());
                batch.setLong(3, now);
                batch.setLong(4, now);
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
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized List<QueuedTransfer> loadQueued() throws SQLException {
        String sql = """
                SELECT t.id, t.batch_id, t.destination, t.amount_nano, t.comment
                FROM transfers t
                JOIN batches b ON b.id = t.batch_id
                WHERE t.status = 'queued' AND b.status IN ('queued', 'sending')
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, total, tx_hash, error FROM batches WHERE id = ?")) {
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

    public synchronized boolean isFailed(String batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM batches WHERE id = ?")) {
            statement.setString(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "failed".equals(result.getString(1));
            }
        }
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
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE batches SET status = ?, tx_hash = ?, updated_at = ? WHERE id = ?")) {
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

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
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

    public record QueuedTransfer(long id, String batchId, Transfer transfer) {
    }

    public record BatchOutcome(String batchId, String status, int count, String txHash, String error, boolean terminal) {
    }
}

package dev.tonpayout.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class EngineConfig {
    private final String httpHost;
    private final int httpPort;
    private final String apiKey;
    private final String walletAddress;
    private final String walletKeyPath;
    private final int subwalletId;
    private final String tonEndpoint;
    private final String tonApiKey;
    private final String tonNetwork;
    private final long batchWindowMs;
    private final int batchMaxSize;
    private final String webhookUrl;
    private final int webhookTimeoutMs;
    private final String dbPath;
    private final long confirmDelayMs;
    private final int confirmAttempts;
    private final long feeReserveNano;
    private final long pauseRetryMs;
    private final String alertUrl;
    private final long sendingStaleMs;
    private final long housekeepingRetentionMs;
    private final long housekeepingIntervalMs;

    private EngineConfig(Properties properties) {
        httpHost = setting(properties, "http.host", "PAYOUT_HTTP_HOST", "0.0.0.0");
        httpPort = parseInt(setting(properties, "http.port", "PAYOUT_HTTP_PORT", "8080"), "http.port", 1, 65535);
        apiKey = setting(properties, "auth.apiKey", "PAYOUT_API_KEY", "");
        walletAddress = setting(properties, "wallet.address", "WALLET_ADDRESS", "");
        walletKeyPath = setting(properties, "wallet.keyPath", "WALLET_KEY_PATH", "secrets/wallet.key");
        subwalletId = parseInt(setting(properties, "wallet.subwalletId", "WALLET_SUBWALLET_ID", "698983191"),
                "wallet.subwalletId", 0, Integer.MAX_VALUE);
        tonEndpoint = setting(properties, "ton.endpoint", "TON_ENDPOINT", "https://toncenter.com/api/v2");
        tonApiKey = setting(properties, "ton.apiKey", "TON_API_KEY", "");
        tonNetwork = setting(properties, "ton.network", "TON_NETWORK", "mainnet");
        batchWindowMs = parseLong(setting(properties, "batch.windowMs", "BATCH_WINDOW_MS", "2500"),
                "batch.windowMs", 100, 60_000);
        batchMaxSize = parseInt(setting(properties, "batch.maxSize", "BATCH_MAX_SIZE", "254"),
                "batch.maxSize", 1, 254);
        webhookUrl = setting(properties, "webhook.url", "WEBHOOK_URL", "");
        webhookTimeoutMs = parseInt(setting(properties, "webhook.timeoutMs", "WEBHOOK_TIMEOUT_MS", "5000"),
                "webhook.timeoutMs", 100, 60_000);
        dbPath = setting(properties, "db.path", "PAYOUT_DB_PATH", "data/payout.db");
        confirmDelayMs = parseLong(setting(properties, "confirm.delayMs", "CONFIRM_DELAY_MS", "12000"),
                "confirm.delayMs", 200, 120_000);
        confirmAttempts = parseInt(setting(properties, "confirm.attempts", "CONFIRM_ATTEMPTS", "4"),
                "confirm.attempts", 1, 30);
        feeReserveNano = parseLong(setting(properties, "wallet.feeReserveNano", "FEE_RESERVE_NANO", "50000000"),
                "wallet.feeReserveNano", 0, 10_000_000_000L);
        pauseRetryMs = parseLong(setting(properties, "pause.retryMs", "PAUSE_RETRY_MS", "15000"),
                "pause.retryMs", 200, 600_000);
        alertUrl = setting(properties, "alert.url", "ALERT_URL", "");
        sendingStaleMs = parseLong(setting(properties, "sending.staleMs", "SENDING_STALE_MS", "300000"),
                "sending.staleMs", 1_000, 86_400_000);
        housekeepingRetentionMs = parseLong(
                setting(properties, "housekeeping.retentionMs", "HOUSEKEEPING_RETENTION_MS", "2592000000"),
                "housekeeping.retentionMs", 1_000, 630_720_000_000L);
        housekeepingIntervalMs = parseLong(
                setting(properties, "housekeeping.intervalMs", "HOUSEKEEPING_INTERVAL_MS", "86400000"),
                "housekeeping.intervalMs", 1_000, 2_592_000_000L);
    }

    public static EngineConfig load() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EngineConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(in);
            }
        }
        Path external = Path.of("application.properties");
        if (Files.isRegularFile(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                properties.load(in);
            }
        }
        return new EngineConfig(properties);
    }

    private static String setting(Properties properties, String key, String env, String fallback) {
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int parseInt(String raw, String name, int min, int max) {
        return (int) parseLong(raw, name, min, max);
    }

    private static long parseLong(String raw, String name, long min, long max) {
        try {
            long value = Long.parseLong(raw);
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not an integer: " + raw);
        }
    }

    public String httpHost() { return httpHost; }
    public int httpPort() { return httpPort; }
    public String apiKey() { return apiKey; }
    public String walletAddress() { return walletAddress; }
    public String walletKeyPath() { return walletKeyPath; }
    public int subwalletId() { return subwalletId; }
    public String tonEndpoint() { return tonEndpoint; }
    public String tonApiKey() { return tonApiKey; }
    public String tonNetwork() { return tonNetwork; }
    public long batchWindowMs() { return batchWindowMs; }
    public int batchMaxSize() { return batchMaxSize; }
    public String webhookUrl() { return webhookUrl; }
    public int webhookTimeoutMs() { return webhookTimeoutMs; }
    public String dbPath() { return dbPath; }
    public long confirmDelayMs() { return confirmDelayMs; }
    public int confirmAttempts() { return confirmAttempts; }
    public long feeReserveNano() { return feeReserveNano; }
    public long pauseRetryMs() { return pauseRetryMs; }
    public String alertUrl() { return alertUrl; }
    public long sendingStaleMs() { return sendingStaleMs; }
    public long housekeepingRetentionMs() { return housekeepingRetentionMs; }
    public long housekeepingIntervalMs() { return housekeepingIntervalMs; }
}

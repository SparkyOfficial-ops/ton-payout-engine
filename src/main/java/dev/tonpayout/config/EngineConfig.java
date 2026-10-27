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
}

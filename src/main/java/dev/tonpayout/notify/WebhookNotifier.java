package dev.tonpayout.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.json.Json;

public final class WebhookNotifier {
    private static final Logger LOG = Logger.getLogger(WebhookNotifier.class.getName());

    private final EngineConfig config;
    private final HttpClient client;

    public WebhookNotifier(EngineConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.webhookTimeoutMs()))
                .build();
    }

    public void batchUpdated(String batchId, String status, int count, String txHash, String error) {
        if (config.webhookUrl().isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batchId);
        body.put("status", status);
        body.put("count", count);
        body.put("txHash", txHash);
        body.put("error", error);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.webhookUrl()))
                .timeout(Duration.ofMillis(config.webhookTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    LOG.log(Level.WARNING, "webhook failed for " + batchId + ": " + ex.getMessage());
                    return null;
                });
    }
}

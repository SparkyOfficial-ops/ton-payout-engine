package dev.tonpayout.chain;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.json.Json;

/**
 * Looks up the wallet's recent transactions in TON Center and matches the
 * external-message hash returned by sendBoc. A match means the message is in a block.
 */
public final class TonCenterBlockInclusion implements BlockInclusion {
    private final EngineConfig config;
    private final HttpClient client;

    public TonCenterBlockInclusion(EngineConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.webhookTimeoutMs()))
                .build();
    }

    @Override
    public boolean allIncluded(List<String> messageHashes) throws Exception {
        if (config.walletAddress().isBlank()) {
            throw new IllegalStateException("wallet address is not set");
        }
        String base = config.tonEndpoint().endsWith("/")
                ? config.tonEndpoint()
                : config.tonEndpoint() + "/";
        String url = base + "getTransactions?address="
                + URLEncoder.encode(config.walletAddress(), StandardCharsets.UTF_8)
                + "&limit=100";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.webhookTimeoutMs()))
                .GET();
        if (!config.tonApiKey().isBlank()) {
            request.header("X-API-Key", config.tonApiKey());
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("getTransactions HTTP " + response.statusCode());
        }
        Map<String, Object> body = Json.asObject(Json.parse(response.body()));
        if (Boolean.FALSE.equals(body.get("ok"))) {
            throw new IOException("getTransactions rejected the request");
        }
        for (String hash : messageHashes) {
            if (!MessageHashes.contains(body.get("result"), hash)) {
                return false;
            }
        }
        return true;
    }
}

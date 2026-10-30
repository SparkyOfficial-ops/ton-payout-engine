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
import java.util.logging.Logger;

import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.json.Json;

/**
 * Looks up the wallet's recent transactions in TON Center.
 * A batch is included only when sendBoc's message hash equals {@code in_msg.hash}
 * or, on nodes that omit that field, {@code in_msg.body_hash}.
 */
public final class TonCenterBlockInclusion implements BlockInclusion {
    private static final Logger LOG = Logger.getLogger(TonCenterBlockInclusion.class.getName());

    private final EngineConfig config;
    private final HttpClient client;

    public TonCenterBlockInclusion(EngineConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.webhookTimeoutMs()))
                .build();
    }

    @Override
    public List<?> recentTransactions() throws Exception {
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
        String raw = response.body();
        Map<String, Object> body = Json.asObject(Json.parse(raw));
        if (Boolean.FALSE.equals(body.get("ok"))) {
            throw new IOException("getTransactions rejected the request");
        }
        Object result = body.get("result");
        if (!(result instanceof List<?> transactions)) {
            throw new IOException("getTransactions result is not a list");
        }
        LOG.info("toncenter getTransactions returned " + transactions.size());
        LOG.info("toncenter first transaction json: " + firstResultObject(raw));
        if (!transactions.isEmpty()) {
            Object first = transactions.get(0);
            LOG.info("toncenter tx[0] in_msg.hash='" + MessageHashes.fieldText(first, "hash")
                    + "' in_msg.body_hash='" + MessageHashes.fieldText(first, "body_hash") + "'");
        }
        return transactions;
    }

    @Override
    public boolean allIncluded(List<String> messageHashes) throws Exception {
        return allIncluded(recentTransactions(), messageHashes);
    }

    @Override
    public boolean allIncluded(List<?> transactions, List<String> messageHashes) {
        for (String hash : messageHashes) {
            if (!contains(transactions, hash)) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(List<?> transactions, String expected) {
        for (Object transaction : transactions) {
            if (MessageHashes.inMessageField(transaction, expected) != null) {
                return true;
            }
        }
        return false;
    }

    /** First object of the {@code result} array, copied from the Toncenter body without rewriting. */
    static String firstResultObject(String json) {
        if (json == null) {
            return "";
        }
        int key = json.indexOf("\"result\"");
        if (key < 0) {
            return "";
        }
        int array = json.indexOf('[', key);
        if (array < 0) {
            return "";
        }
        int start = array + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) == ']') {
            return "";
        }
        if (json.charAt(start) != '{') {
            return json.substring(start, Math.min(json.length(), start + 500));
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return json.substring(start);
    }
}

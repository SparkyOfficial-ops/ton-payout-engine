package dev.tonpayout.chain;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.json.Json;
import dev.tonpayout.metrics.EngineMetrics;

/**
 * Looks up the wallet's transactions in TON Center.
 * A batch is included only when sendBoc's message hash equals {@code in_msg.hash}
 * or, on nodes that omit that field, {@code in_msg.body_hash}.
 * The first request is the latest {@value #PAGE_LIMIT} transactions. When a hash is
 * older than that page, the next request starts at the last transaction's
 * {@code transaction_id.lt} and {@code transaction_id.hash}.
 */
public final class TonCenterBlockInclusion implements BlockInclusion {
    private static final Logger LOG = Logger.getLogger(TonCenterBlockInclusion.class.getName());
    static final int PAGE_LIMIT = 100;
    /** First page plus older pages. Stops a poll from walking the whole account. */
    private static final int MAX_PAGES = 30;
    /** Seconds of slack so a transaction at the edge of the message TTL is still visible. */
    private static final long WINDOW_SLACK_SECONDS = 120;

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
        WalletHistory history = new WalletHistory();
        List<?> page = fetchPage(null, null);
        history.addAll(page);
        if (page.size() < PAGE_LIMIT) {
            history.exhausted = true;
        }
        return history;
    }

    @Override
    public boolean allIncluded(List<String> messageHashes) throws Exception {
        return allIncluded(recentTransactions(), messageHashes);
    }

    @Override
    public boolean allIncluded(List<?> transactions, List<String> messageHashes) throws Exception {
        if (messageHashes.isEmpty()) {
            return true;
        }
        long minUtime = System.currentTimeMillis() / 1000L
                - config.messageTtlMs() / 1000L
                - WINDOW_SLACK_SECONDS;
        return find(historyOf(transactions), messageHashes, this::fetchPage, minUtime, MAX_PAGES - 1);
    }

    /**
     * @param minUtimeSeconds skip pages older than this; a payout message cannot be there
     * @param maxExtraPages older pages after the history already in {@code history}
     */
    static boolean find(WalletHistory history, List<String> messageHashes, PageLoader loader,
            long minUtimeSeconds, int maxExtraPages) throws Exception {
        if (messageHashes.isEmpty() || containsAll(history, messageHashes)) {
            return true;
        }
        String previousLt = null;
        String previousHash = null;
        for (int extra = 0; extra < maxExtraPages && !history.exhausted; extra++) {
            if (history.isEmpty()) {
                history.exhausted = true;
                return false;
            }
            Object oldest = history.get(history.size() - 1);
            if (beforeWindow(oldest, minUtimeSeconds)) {
                history.exhausted = true;
                return false;
            }
            String lt = logicalTime(oldest);
            String hash = transactionHash(oldest);
            if (lt == null || hash == null || sameCursor(lt, hash, previousLt, previousHash)) {
                history.exhausted = true;
                return false;
            }
            List<?> page = loader.load(lt, hash);
            int added = history.appendAfterCursor(page, lt, hash);
            LOG.info("toncenter getTransactions before lt " + lt + " returned " + page.size()
                    + ", new " + added);
            if (containsAll(history, messageHashes)) {
                return true;
            }
            if (added == 0 || page.size() < PAGE_LIMIT) {
                history.exhausted = true;
                return false;
            }
            previousLt = lt;
            previousHash = hash;
        }
        return false;
    }

    static String transactionsUrl(String endpoint, String address, String lt, String hash) {
        String base = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        StringBuilder url = new StringBuilder(base)
                .append("getTransactions?address=")
                .append(URLEncoder.encode(address, StandardCharsets.UTF_8))
                .append("&limit=")
                .append(PAGE_LIMIT);
        if (lt != null && hash != null) {
            url.append("&lt=").append(URLEncoder.encode(lt, StandardCharsets.UTF_8));
            url.append("&hash=").append(URLEncoder.encode(hash, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private List<?> fetchPage(String lt, String hash) throws Exception {
        if (config.walletAddress().isBlank()) {
            throw new IllegalStateException("wallet address is not set");
        }
        String url = transactionsUrl(config.tonEndpoint(), config.walletAddress(), lt, hash);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.webhookTimeoutMs()))
                .GET();
        if (!config.tonApiKey().isBlank()) {
            request.header("X-API-Key", config.tonApiKey());
        }
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            EngineMetrics.instance().toncenterError();
            throw e;
        }
        if (response.statusCode() / 100 != 2) {
            EngineMetrics.instance().toncenterError();
            throw new IOException("getTransactions HTTP " + response.statusCode());
        }
        String raw = response.body();
        Map<String, Object> body;
        try {
            body = Json.asObject(Json.parse(raw));
        } catch (IllegalArgumentException e) {
            EngineMetrics.instance().toncenterError();
            throw new IOException("getTransactions returned invalid JSON", e);
        }
        if (Boolean.FALSE.equals(body.get("ok"))) {
            EngineMetrics.instance().toncenterError();
            throw new IOException("getTransactions rejected the request");
        }
        Object result = body.get("result");
        if (!(result instanceof List<?> transactions)) {
            EngineMetrics.instance().toncenterError();
            throw new IOException("getTransactions result is not a list");
        }
        if (lt == null) {
            LOG.info("toncenter getTransactions returned " + transactions.size());
            LOG.info("toncenter first transaction json: " + firstResultObject(raw));
            if (!transactions.isEmpty()) {
                Object first = transactions.get(0);
                LOG.info("toncenter tx[0] in_msg.hash='" + MessageHashes.fieldText(first, "hash")
                        + "' in_msg.body_hash='" + MessageHashes.fieldText(first, "body_hash") + "'");
            }
        }
        return transactions;
    }

    static WalletHistory historyOf(List<?> transactions) {
        if (transactions instanceof WalletHistory history) {
            return history;
        }
        WalletHistory history = new WalletHistory();
        if (transactions != null) {
            history.addAll(transactions);
        }
        if (transactions == null || transactions.size() < PAGE_LIMIT) {
            history.exhausted = true;
        }
        return history;
    }

    private static boolean containsAll(List<?> transactions, List<String> messageHashes) {
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

    private static boolean beforeWindow(Object transaction, long minUtimeSeconds) {
        Long utime = utime(transaction);
        return utime != null && utime < minUtimeSeconds;
    }

    static Long utime(Object transaction) {
        if (!(transaction instanceof Map<?, ?> tx)) {
            return null;
        }
        return wholeNumber(tx.get("utime"));
    }

    /** Decimal {@code transaction_id.lt}. Logical time does not fit in a signed long. */
    static String logicalTime(Object transaction) {
        Object id = transactionId(transaction);
        if (!(id instanceof Map<?, ?> txid)) {
            return null;
        }
        return decimal(txid.get("lt"));
    }

    /** {@code transaction_id.hash}, the pagination cursor. Not the message hash. */
    static String transactionHash(Object transaction) {
        Object id = transactionId(transaction);
        if (!(id instanceof Map<?, ?> txid)) {
            return null;
        }
        Object hash = txid.get("hash");
        if (hash == null) {
            return null;
        }
        String text = String.valueOf(hash).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static Object transactionId(Object transaction) {
        if (!(transaction instanceof Map<?, ?> tx)) {
            return null;
        }
        return tx.get("transaction_id");
    }

    private static boolean sameCursor(String lt, String hash, String previousLt, String previousHash) {
        if (previousLt == null || previousHash == null) {
            return false;
        }
        if (!lt.equals(previousLt)) {
            return false;
        }
        String left = MessageHashes.normalize(hash);
        String right = MessageHashes.normalize(previousHash);
        if (!left.isEmpty() || !right.isEmpty()) {
            return left.equals(right);
        }
        return hash.equals(previousHash);
    }

    private static boolean sameCursor(Object transaction, String lt, String hash) {
        String actualLt = logicalTime(transaction);
        String actualHash = transactionHash(transaction);
        return actualLt != null && actualHash != null && sameCursor(actualLt, actualHash, lt, hash);
    }

    private static String decimal(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof Number) {
            text = new java.math.BigDecimal(value.toString()).toBigInteger().toString();
        } else {
            text = String.valueOf(value).trim();
        }
        if (text.isEmpty() || !text.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return text;
    }

    private static Long wholeNumber(Object value) {
        String text = decimal(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
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

    @FunctionalInterface
    interface PageLoader {
        List<?> load(String lt, String hash) throws Exception;
    }

    static final class WalletHistory extends ArrayList<Object> {
        boolean exhausted;

        int appendAfterCursor(List<?> page, String lt, String hash) {
            int added = 0;
            boolean first = true;
            for (Object transaction : page) {
                if (first && sameCursor(transaction, lt, hash)) {
                    first = false;
                    continue;
                }
                first = false;
                add(transaction);
                added++;
            }
            return added;
        }
    }
}

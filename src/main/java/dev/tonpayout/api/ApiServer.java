package dev.tonpayout.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.tonpayout.chain.ChainSender;
import dev.tonpayout.chain.TonAddress;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.json.Json;
import dev.tonpayout.queue.PayoutBatcher;
import dev.tonpayout.queue.PayoutStore;
import dev.tonpayout.queue.PayoutStore.BatchOutcome;
import dev.tonpayout.queue.SendingRecovery;
import dev.tonpayout.queue.Transfer;

public final class ApiServer {
    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    private static final int MAX_BODY = 1_048_576;

    private final EngineConfig config;
    private final PayoutBatcher batcher;
    private final ChainSender chain;
    private final SendingRecovery recovery;
    private final PayoutStore store;
    private HttpServer server;
    private ExecutorService httpExecutor;

    public ApiServer(EngineConfig config, PayoutBatcher batcher, ChainSender chain, SendingRecovery recovery,
            PayoutStore store) {
        this.config = config;
        this.batcher = batcher;
        this.chain = chain;
        this.recovery = recovery;
        this.store = store;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.httpHost(), config.httpPort()), 0);
        server.createContext("/health", this::health);
        server.createContext("/api/v1/payout", this::payout);
        server.createContext("/api/v1/status", this::status);
        server.createContext("/api/v1/balance", this::balance);
        server.createContext("/api/v1/recovery", this::recovery);
        httpExecutor = Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(httpExecutor);
        server.start();
        LOG.info("http " + config.httpHost() + ":" + config.httpPort()
                + " wallet=" + (config.walletAddress().isBlank() ? "unset" : config.walletAddress())
                + " network=" + config.tonNetwork());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (httpExecutor != null) {
            httpExecutor.shutdown();
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, error("method not allowed"));
            return;
        }
        boolean database = true;
        try {
            store.ping();
        } catch (Exception e) {
            database = false;
            LOG.log(Level.WARNING, "health check failed: database is not reachable", e);
        }
        boolean wallet = chain.isReady();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", database && wallet ? "up" : "down");
        body.put("database", database ? "up" : "down");
        body.put("wallet", wallet ? "ready" : "missing");
        send(exchange, database && wallet ? 200 : 503, body);
    }

    private void payout(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, error("method not allowed"));
            return;
        }
        String key = exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
        if (key == null || key.isBlank()) {
            send(exchange, 400, error("X-Idempotency-Key is required"));
            return;
        }
        key = key.trim();
        if (key.length() > 200) {
            send(exchange, 400, error("X-Idempotency-Key is too long"));
            return;
        }
        String raw = readBody(exchange);
        if (raw == null) {
            send(exchange, 413, error("body is too large"));
            return;
        }
        try {
            List<Transfer> transfers = parseTransfers(raw);
            PayoutBatcher.AcceptResult accepted = batcher.enqueue(key, transfers);
            send(exchange, accepted.created() ? 202 : 200, accepted.status());
        } catch (IllegalArgumentException e) {
            send(exchange, 400, error(e.getMessage()));
        } catch (PayoutBatcher.IdempotencyConflict e) {
            send(exchange, 409, error(e.getMessage()));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "failed to store payout", e);
            send(exchange, 500, error("failed to store payout"));
        }
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, error("method not allowed"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/v1/status/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            send(exchange, 404, error("batch id required"));
            return;
        }
        try {
            Map<String, Object> body = batcher.status(path.substring(prefix.length()));
            if (body == null) {
                send(exchange, 404, error("batch not found"));
                return;
            }
            send(exchange, 200, body);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "failed to read payout status", e);
            send(exchange, 500, error("failed to read payout status"));
        }
    }

    private void balance(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, error("method not allowed"));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("address", config.walletAddress());
        body.put("network", config.tonNetwork());
        body.put("endpoint", config.tonEndpoint());
        try {
            body.put("balanceNano", chain.balanceNano());
            send(exchange, 200, body);
        } catch (Exception e) {
            body.put("balanceNano", null);
            body.put("error", e.getMessage());
            send(exchange, 502, body);
        }
    }

    private void recovery(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, error("method not allowed"));
            return;
        }
        try {
            List<Map<String, Object>> resolved = new ArrayList<>();
            for (BatchOutcome outcome : recovery.sweep()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchId", outcome.batchId());
                row.put("status", outcome.status());
                row.put("txHash", outcome.txHash());
                row.put("error", outcome.error());
                resolved.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resolved", resolved);
            send(exchange, 200, body);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "sending recovery failed", e);
            send(exchange, 500, error("sending recovery failed"));
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (config.apiKey().isBlank()) {
            send(exchange, 503, error("PAYOUT_API_KEY is not set"));
            return false;
        }
        String provided = exchange.getRequestHeaders().getFirst("X-Api-Key");
        if (provided == null || !constantTimeEquals(config.apiKey(), provided.trim())) {
            send(exchange, 401, error("invalid api key"));
            return false;
        }
        return true;
    }

    private List<Transfer> parseTransfers(String raw) {
        Map<String, Object> root = Json.asObject(Json.parse(raw));
        Object rawTransfers = root.get("transfers");
        if (!(rawTransfers instanceof List)) {
            throw new IllegalArgumentException("transfers array is required");
        }
        List<?> list = (List<?>) rawTransfers;
        if (list.isEmpty()) {
            throw new IllegalArgumentException("transfers must not be empty");
        }
        if (list.size() > config.batchMaxSize()) {
            throw new IllegalArgumentException(
                    "transfers count exceeds maximum highload batch size (" + config.batchMaxSize() + ")");
        }
        List<Transfer> transfers = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> row = Json.asObject(item);
            String to = row.get("to") != null ? text(row.get("to")) : text(row.get("address"));
            String amount = nanoAmount(row);
            String comment = row.get("comment") == null ? "" : text(row.get("comment"));
            String destination = TonAddress.canonical(to, config.tonNetwork());
            if (destination == null) {
                throw new IllegalArgumentException("invalid destination");
            }
            to = destination;
            if (comment.length() > 120) {
                throw new IllegalArgumentException("comment is too long");
            }
            transfers.add(new Transfer(to, amount, comment));
        }
        return transfers;
    }

    private static String nanoAmount(Map<String, Object> row) {
        if (row.get("amountNano") != null) {
            String amount = text(row.get("amountNano")).trim();
            try {
                if (new BigInteger(amount).signum() <= 0) {
                    throw new IllegalArgumentException("amountNano must be positive");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("amountNano must be a positive integer string");
            }
            return amount;
        }
        if (row.get("amount") == null) {
            throw new IllegalArgumentException("amount or amountNano is required");
        }
        try {
            BigDecimal ton = new BigDecimal(text(row.get("amount")).trim());
            if (ton.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            return ton.movePointRight(9).toBigIntegerExact().toString();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("amount must be a positive TON value with at most 9 decimal places");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("missing field");
        }
        return String.valueOf(value);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        int length = 0;
        try (InputStream in = exchange.getRequestBody()) {
            byte[] chunk = in.readNBytes(MAX_BODY + 1);
            length = chunk.length;
            if (length > MAX_BODY) {
                return null;
            }
            return new String(chunk, 0, length, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    private static void send(HttpExchange exchange, int code, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            byte av = i < a.length ? a[i] : 0;
            byte bv = i < b.length ? b[i] : 0;
            diff |= av ^ bv;
        }
        return diff == 0;
    }
}

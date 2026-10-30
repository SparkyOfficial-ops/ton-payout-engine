package dev.tonpayout.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.tonpayout.chain.BlockInclusion;
import dev.tonpayout.chain.ChainSender;
import dev.tonpayout.chain.ChainSender.Outgoing;
import dev.tonpayout.config.EngineConfig;
import dev.tonpayout.json.Json;
import dev.tonpayout.notify.WebhookNotifier;
import dev.tonpayout.queue.PayoutBatcher;
import dev.tonpayout.queue.PayoutStore;
import dev.tonpayout.queue.SendingRecovery;
import dev.tonpayout.queue.Transfer;

class ApiValidationTest {
    private static final String TO = "0:" + "ab".repeat(32);
    private static final Path DB = Path.of("target/junit-api.db");

    private static EngineConfig config;
    private static PayoutStore store;
    private static ApiServer server;
    private static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        deleteDb(DB);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Properties properties = new Properties();
        properties.setProperty("http.host", "127.0.0.1");
        properties.setProperty("http.port", Integer.toString(port));
        properties.setProperty("auth.apiKey", "test-key");
        properties.setProperty("db.path", DB.toString());
        properties.setProperty("ton.network", "mainnet");
        config = EngineConfig.from(properties);
        store = new PayoutStore(DB);
        ChainSender chain = new ChainSender() {
            public boolean isReady() {
                return true;
            }

            public Outgoing prepare(List<Transfer> transfers) {
                return new Outgoing("ab".repeat(32), new byte[] {1});
            }

            public void broadcast(Outgoing message) {
            }

            public String balanceNano() {
                return "1000000000000";
            }
        };
        WebhookNotifier webhook = new WebhookNotifier(config);
        PayoutBatcher batcher = new PayoutBatcher(config, chain, webhook, store);
        BlockInclusion inclusion = hashes -> true;
        server = new ApiServer(config, batcher, chain, new SendingRecovery(config, store, webhook, inclusion), store);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void tonAmountsKeepEveryNanoton() {
        assertEquals("100000000", ApiServer.nanoAmount(amount("0.1")));
        assertEquals("1", ApiServer.nanoAmount(amount("0.000000001")));
    }

    @Test
    void postedAmountsAreStoredExactly() throws Exception {
        String body = "{\"transfers\":["
                + "{\"to\":\"" + TO + "\",\"amount\":\"0.1\"},"
                + "{\"to\":\"" + TO + "\",\"amount\":\"0.000000001\"}]}";
        HttpResponse<String> response = post("exact", body);
        assertEquals(202, response.statusCode());
        assertEquals(List.of("100000000", "1"), storedAmounts());
    }

    @Test
    void tenDecimalPlacesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ApiServer.nanoAmount(amount("0.0000000001")));
    }

    @Test
    void twoHundredFiftyFiveTransfersAreBadRequest() throws Exception {
        StringBuilder body = new StringBuilder("{\"transfers\":[");
        for (int i = 0; i < 255; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"to\":\"").append(TO).append("\",\"amount\":\"0.1\"}");
        }
        body.append("]}");
        HttpResponse<String> response = post("too-many", body.toString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("exceeds maximum highload batch size"));
        assertNull(store.findIdempotent("too-many"));
    }

    private static Map<String, Object> amount(String ton) {
        return Json.asObject(Json.parse("{\"amount\":\"" + ton + "\"}"));
    }

    private static HttpResponse<String> post(String key, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + config.httpPort() + "/api/v1/payout"))
                .header("X-Api-Key", config.apiKey())
                .header("X-Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
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

    private static List<String> storedAmounts() throws Exception {
        List<String> amounts = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DB.toAbsolutePath());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT amount_nano FROM transfers ORDER BY id")) {
            while (rows.next()) {
                amounts.add(rows.getString(1));
            }
        }
        return amounts;
    }
}

package ua.kpi.search;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class OllamaTest {
    @TempDir Path corpus;
    private HttpServer fixture;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private URI base;
    private OllamaClient client;
    private final AtomicReference<JsonObject> payload = new AtomicReference<>();

    @BeforeEach void open() throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.setExecutor(handlers);
        fixture.start();
        base = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort());
        client = new OllamaClient(base, "example:latest", Duration.ofSeconds(2));
    }

    @AfterEach void close() {
        fixture.stop(0);
        handlers.close();
    }

    private void endpoint(String path, int status, String body, long delayMs) {
        fixture.createContext(path, exchange -> {
            try (exchange) {
                if (path.equals("/api/generate")) {
                    payload.set(Wire.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                }
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    @Test void promptSchemaAndFilteringBoundGeneratedVocabulary() {
        endpoint("/api/tags", 200, "{\"models\":[{\"name\":\"example:latest\"}]}", 0);
        String generated = """
                {"terms":["CAR","Wheel","wheel","two words","fine!","tire",7,
                "drive","engine","road","fuel","extra"]}
                """;
        endpoint("/api/generate", 200, Wire.JSON.toJson(Map.of("response", generated)), 0);
        assertTrue(client.ready());
        assertEquals(List.of("wheel", "tire", "drive", "engine", "road", "fuel"), client.expand("car"));
        JsonObject sent = payload.get();
        assertFalse(sent.get("stream").getAsBoolean());
        assertEquals("example:latest", sent.get("model").getAsString());
        assertTrue(sent.get("format").isJsonObject());
        assertEquals("car", Wire.object(sent.get("prompt").getAsString()).get("query").getAsString());
        assertEquals(0, sent.getAsJsonObject("options").get("temperature").getAsInt());
    }

    @Test void concurrentQueriesTakeTurnsUsingTheModel() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        fixture.createContext("/api/generate", exchange -> {
            try (exchange) {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                exchange.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                byte[] bytes = Wire.JSON.toJson(Map.of("response", "{\"terms\":[\"wheel\"]}"))
                        .getBytes(StandardCharsets.UTF_8);
                active.decrementAndGet();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        CountDownLatch start = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(i -> callers.submit(() -> {
                start.await();
                return client.expand("car");
            })).toList();
            start.countDown();
            for (var task : tasks) {
                assertEquals(List.of("wheel"), task.get());
            }
            assertEquals(1, peak.get());
        }
    }

    @Test void malformedAndSlowGenerationFallBackToOriginal() {
        endpoint("/api/tags", 200, "{\"models\":[{\"name\":\"another:latest\"}]}", 0);
        endpoint("/api/generate", 200, "{\"response\":\"broken\"}", 0);
        assertFalse(client.ready());
        assertTrue(client.expand("orbit").isEmpty());
        fixture.removeContext("/api/generate");
        endpoint("/api/generate", 200, "{}", 1200);
        var impatient = new OllamaClient(base, "example:latest", Duration.ofMillis(100));
        long start = System.nanoTime();
        assertTrue(impatient.expand("orbit").isEmpty());
        assertTrue(SearchEngine.milliseconds(start) < 900);
    }

    @Test void unavailableModelStillCompletesAllStagesAndFindsTheOriginalWord() throws Exception {
        endpoint("/api/tags", 503, "{}", 0);
        endpoint("/api/generate", 503, "{}", 0);
        Files.writeString(corpus.resolve("sky.txt"), "A satellite enters orbit.");
        try (var server = new Server("127.0.0.1", 0, corpus, base, "example", Duration.ofSeconds(1));
             var http = HttpClient.newHttpClient()) {
            server.start();
            URI api = URI.create("http://127.0.0.1:" + server.port());
            var health = http.send(HttpRequest.newBuilder(api.resolve("/api/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals(Wire.object("{\"status\":\"ok\",\"ollama\":false}"), Wire.object(health.body()));
            var indexed = http.send(HttpRequest.newBuilder(api.resolve("/api/index"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"threads\":2}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, indexed.statusCode());
            var messages = new LinkedBlockingQueue<String>();
            WebSocket socket = http.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:" + server.port() + "/ws/search"), new WebSocket.Listener() {
                        final StringBuilder parts = new StringBuilder();
                        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            parts.append(data);
                            if (last) {
                                messages.add(parts.toString());
                                parts.setLength(0);
                            }
                            ws.request(1);
                            return null;
                        }
                    }).join();
            try {
                socket.sendText("{\"query\":\"orbit\"}", true).join();
                var first = Wire.object(messages.poll(5, TimeUnit.SECONDS));
                assertEquals(Wire.object("""
                        {"stage":"query_expansion","data":{"original":"orbit","expanded_terms":[]}}
                        """), first);
                var second = Wire.object(messages.poll(5, TimeUnit.SECONDS));
                assertEquals("keyword_search", second.get("stage").getAsString());
                assertEquals("sky.txt", second.getAsJsonObject("data").getAsJsonArray("results")
                        .get(0).getAsJsonObject().get("doc_id").getAsString());
                assertEquals(Wire.object("{\"stage\":\"done\"}"), Wire.object(messages.poll(5, TimeUnit.SECONDS)));
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "finished").join();
            }
        }
    }
}

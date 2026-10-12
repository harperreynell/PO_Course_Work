package ua.kpi.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class Server implements AutoCloseable {
    private final SearchEngine engine;
    private final OllamaClient ollama;
    private final Undertow server;

    Server(String host, int port, Path directory, URI modelUrl, String model, Duration timeout) {
        engine = new SearchEngine(directory);
        ollama = new OllamaClient(modelUrl, model, timeout);
        var routes = Handlers.path(new BlockingHandler(this::handle))
                .addExactPath("/ws/search", Handlers.websocket((exchange, channel) -> {
                    channel.getReceiveSetter().set(new SearchSocket(engine, ollama));
                    channel.resumeReceives();
                }));
        server = Undertow.builder().addHttpListener(port, host).setIoThreads(2)
                .setWorkerThreads(32).setHandler(routes).build();
    }

    public static void main(String[] args) {
        var env = System.getenv();
        Server app = new Server("0.0.0.0", Integer.parseInt(env.getOrDefault("PORT", "8080")),
                Path.of(env.getOrDefault("DATA_DIR", "data/sample")),
                URI.create(env.getOrDefault("OLLAMA_URL", "http://localhost:11434")),
                env.getOrDefault("OLLAMA_MODEL", "qwen2.5:0.5b"),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("OLLAMA_TIMEOUT_MS", "45000"))));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        app.start();
    }

    void start() {
        server.start();
    }

    int port() {
        return ((InetSocketAddress) server.getListenerInfo().getFirst().getAddress()).getPort();
    }

    @Override public void close() {
        server.stop();
    }

    private void handle(HttpServerExchange exchange) throws IOException {
        String path = exchange.getRequestPath();
        String method = switch (path) {
            case "/api/index", "/api/search" -> "POST";
            case "/api/health", "/api/stats", "/", "/app.js", "/style.css" -> "GET";
            default -> "";
        };
        if (method.isEmpty()) {
            error(exchange, 404, "unknown path");
            return;
        }
        if (!exchange.getRequestMethod().equalToString(method)) {
            exchange.getResponseHeaders().put(Headers.ALLOW, method);
            error(exchange, 405, "method must be " + method);
            return;
        }
        try {
            if (method.equals("POST")) {
                byte[] bytes = exchange.getInputStream().readNBytes(Wire.MAX_BODY + 1);
                if (bytes.length > Wire.MAX_BODY) {
                    error(exchange, 413, "request body is too large");
                    return;
                }
                JsonObject body = Wire.object(new String(bytes, StandardCharsets.UTF_8));
                if (path.equals("/api/index")) {
                    json(exchange, engine.rebuild(Wire.threads(body)));
                } else {
                    if (!Wire.string(body, "mode").equals("keyword")) {
                        throw new IllegalArgumentException("mode must be keyword");
                    }
                    json(exchange, engine.search(Wire.query(body)));
                }
            } else {
                switch (path) {
                    case "/api/health" -> json(exchange, Map.of("status", "ok", "ollama", ollama.ready()));
                    case "/api/stats" -> json(exchange, engine.stats());
                    default -> asset(exchange, path);
                }
            }
        } catch (JsonParseException | IllegalArgumentException error) {
            error(exchange, 400, "invalid request: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            error(exchange, 503, "indexing interrupted");
        } catch (Exception error) {
            error.printStackTrace(System.err);
            error(exchange, 500, "operation failed; see server log");
        }
    }

    private static void asset(HttpServerExchange exchange, String path) throws IOException {
        String name = path.equals("/") ? "/index.html" : path;
        String type = switch (name) {
            case "/app.js" -> "text/javascript";
            case "/style.css" -> "text/css";
            default -> "text/html";
        };
        try (InputStream stream = Server.class.getResourceAsStream("/web" + name)) {
            if (stream == null) {
                error(exchange, 404, "missing asset");
                return;
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, type + "; charset=utf-8");
            exchange.getOutputStream().write(stream.readAllBytes());
        }
    }

    private static void json(HttpServerExchange exchange, Object value) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(Wire.JSON.toJson(value), StandardCharsets.UTF_8);
    }

    private static void error(HttpServerExchange exchange, int status, String message) {
        exchange.setStatusCode(status);
        json(exchange, Map.of("error", message));
    }
}

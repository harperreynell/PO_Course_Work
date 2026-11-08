package ua.kpi.search;

import com.google.gson.JsonParseException;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import java.io.IOException;
import java.util.List;
import java.util.Map;

final class SearchSocket extends AbstractReceiveListener {
    private final SearchEngine engine;
    private final OllamaClient ollama;

    SearchSocket(SearchEngine engine, OllamaClient ollama) {
        this.engine = engine;
        this.ollama = ollama;
    }

    @Override protected long getMaxTextBufferSize() {
        return Wire.MAX_BODY;
    }

    @Override protected long getMaxBinaryBufferSize() {
        return Wire.MAX_BODY;
    }

    @Override protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
        String raw = message.getData();
        channel.suspendReceives();
        Thread.startVirtualThread(() -> {
            try {
                String query = Wire.query(Wire.object(raw));
                List<String> additions = ollama.expand(query);
                send(channel, "query_expansion", Map.of("original", query, "expanded_terms", additions));
                send(channel, "keyword_search", engine.search(query + " " + String.join(" ", additions)));
                WebSockets.sendTextBlocking("{\"stage\":\"done\"}", channel);
                channel.resumeReceives();
            } catch (IllegalArgumentException | JsonParseException error) {
                WebSockets.sendClose(1007, "Invalid search query", channel, null);
            } catch (IOException | RuntimeException error) {
                WebSockets.sendClose(1011, "Search interrupted", channel, null);
            }
        });
    }

    private static void send(WebSocketChannel channel, String stage, Object data) throws IOException {
        WebSockets.sendTextBlocking(Wire.JSON.toJson(Map.of("stage", stage, "data", data)), channel);
    }
}

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BackendLite {
    private static final Map<String, List<ChatMessage>> ROOM_MESSAGES = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/messages", BackendLite::handleMessages);
        server.setExecutor(null);
        server.start();
        System.out.println("BackendLite running on http://localhost:8080");
    }

    private static void handleMessages(HttpExchange exchange) throws IOException {
        addCors(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/messages/")) {
            String roomId = path.substring("/api/messages/".length());
            List<ChatMessage> list = ROOM_MESSAGES.getOrDefault(roomId, List.of());
            sendJson(exchange, 200, messagesToJson(list));
            return;
        }

        if ("POST".equalsIgnoreCase(method) && "/api/messages".equals(path)) {
            String body = readBody(exchange.getRequestBody());
            String roomId = jsonValue(body, "roomId");
            String sender = jsonValue(body, "sender");
            String content = jsonValue(body, "content");

            if (roomId.isBlank() || sender.isBlank() || content.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"roomId, sender and content are required\"}");
                return;
            }

            ChatMessage msg = new ChatMessage(
                    UUID.randomUUID().toString(),
                    roomId,
                    sender,
                    content,
                    Instant.now().toEpochMilli()
            );
            ROOM_MESSAGES.computeIfAbsent(roomId, key -> new ArrayList<>()).add(msg);
            sendJson(exchange, 200, messageToJson(msg));
            return;
        }

        sendJson(exchange, 404, "{\"error\":\"Not found\"}");
    }

    private static void addCors(Headers headers) {
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");
        headers.add("Content-Type", "application/json; charset=utf-8");
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String jsonValue(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) return "";
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return "";
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return "";
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return "";
        return json.substring(firstQuote + 1, secondQuote).trim();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String messageToJson(ChatMessage m) {
        return "{"
                + "\"id\":\"" + escape(m.id) + "\","
                + "\"roomId\":\"" + escape(m.roomId) + "\","
                + "\"sender\":\"" + escape(m.sender) + "\","
                + "\"content\":\"" + escape(m.content) + "\","
                + "\"timestamp\":" + m.timestamp
                + "}";
    }

    private static String messagesToJson(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(messageToJson(messages.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static final class ChatMessage {
        private final String id;
        private final String roomId;
        private final String sender;
        private final String content;
        private final long timestamp;

        private ChatMessage(String id, String roomId, String sender, String content, long timestamp) {
            this.id = id;
            this.roomId = roomId;
            this.sender = sender;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}

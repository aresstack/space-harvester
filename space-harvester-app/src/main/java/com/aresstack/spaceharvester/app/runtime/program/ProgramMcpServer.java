package com.aresstack.spaceharvester.app.runtime.program;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly started loopback-only MCP 2025-11-25 Streamable HTTP endpoint, JSON responses only. */
public final class ProgramMcpServer implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
    private static final int REQUEST_BYTES = 64 * 1024;
    private static final int RESPONSE_BYTES = 1024 * 1024;
    private final ProgramMcpTools tools;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "program-mcp");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String token;

    public ProgramMcpServer(ProgramMcpTools tools) throws IOException {
        this.tools = java.util.Objects.requireNonNull(tools);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(workers);
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }
    public String token() { return token; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (closed.get() || !"/mcp".equals(exchange.getRequestURI().getRawPath())
                    || exchange.getRequestURI().getRawQuery() != null) { empty(exchange, 404); return; }
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + server.getAddress().getPort()).equals(host)
                    || exchange.getRequestHeaders().containsKey("Origin")) { empty(exchange, 403); return; }
            var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                empty(exchange, 401);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                empty(exchange, 405);
                return;
            }
            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !VERSIONS.contains(version)) { empty(exchange, 400); return; }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].strip())) {
                empty(exchange, 415);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(REQUEST_BYTES + 1);
            if (body.length > REQUEST_BYTES) { empty(exchange, 413); return; }
            JsonNode request;
            try { request = JSON.readTree(body); }
            catch (RuntimeException invalid) { send(exchange, error(null, -32700, "Parse error")); return; }
            if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asString(""))
                    || !request.path("method").isString()) {
                send(exchange, error(null, -32600, "Invalid request"));
                return;
            }
            var id = request.get("id");
            if (id == null) {
                // Notifications have no JSON-RPC response.
                empty(exchange, 202);
                return;
            }
            if (!id.isString() && !id.isIntegralNumber()) {
                send(exchange, error(null, -32600, "Invalid request ID"));
                return;
            }
            String method = request.path("method").asString();
            var params = request.path("params");
            Object result;
            switch (method) {
                case "initialize" -> {
                    String requested = params.path("protocolVersion").asString("");
                    if (requested.isBlank()) { send(exchange, error(id, -32602, "Missing protocolVersion")); return; }
                    result = Map.of("protocolVersion", VERSIONS.contains(requested) ? requested : "2025-11-25",
                            "capabilities", Map.of("tools", Map.of("listChanged", false)),
                            "serverInfo", Map.of("name", "space-harvester-programs", "version", "1.0.0"));
                }
                case "ping" -> result = Map.of();
                case "tools/list" -> result = Map.of("tools", tools.definitions());
                case "tools/call" -> {
                    try {
                        if (!params.path("name").isString()) throw new IllegalArgumentException();
                        JsonNode arguments = params.path("arguments");
                        if (arguments.isMissingNode()) arguments = JSON.createObjectNode();
                        Object value = tools.call(params.path("name").asString(), arguments, closed::get);
                        String text = JSON.writeValueAsString(value);
                        if (text.getBytes(StandardCharsets.UTF_8).length > RESPONSE_BYTES / 3) {
                            result = toolError("Response too large; reduce limit. No partial result returned.");
                        } else {
                            result = Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false);
                        }
                    } catch (IllegalArgumentException invalid) {
                        result = toolError("Invalid tool arguments or unsupported section.");
                    } catch (RuntimeException unavailable) {
                        result = toolError("Current authorized program evidence unavailable or request cancelled.");
                    }
                }
                default -> { send(exchange, error(id, -32601, "Method not found")); return; }
            }
            if (closed.get()) { empty(exchange, 503); return; }
            send(exchange, Map.of("jsonrpc", "2.0", "id", id, "result", result));
        }
    }

    private static Map<String, Object> toolError(String message) {
        return Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", message)));
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("jsonrpc", "2.0");
        value.put("id", id);
        value.put("error", Map.of("code", code, "message", message));
        return value;
    }

    private static void send(HttpExchange exchange, Object response) throws IOException {
        byte[] data = JSON.writeValueAsBytes(response);
        if (data.length > RESPONSE_BYTES) { empty(exchange, 413); return; }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, data.length);
        exchange.getResponseBody().write(data);
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        workers.shutdownNow();
    }
}

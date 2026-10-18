package com.aresstack.enterpriseai.embedding.openai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lokaler {@code /v1/embeddings}-Fake auf 127.0.0.1 mit zufälligem Port. Antwortet im OpenAI-Format mit einem
 * deterministischen Vektor je Text und zeichnet jeden Request (Header, Body-Bytes) auf. Über
 * {@link #failNext} lassen sich Fehlerantworten erzwingen; im String-only-Modus lehnt er Array-Input mit 422 ab
 * (so, wie die OpenAPI-Typdefinition {@code input: string} es nahelegen würde).
 */
final class FakeEmbeddingServer implements AutoCloseable {

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Recorded {
        final String method;
        final String path;
        final String contentType;
        final String authorization;
        final String accept;
        final String userAgent;
        final byte[] body;

        Recorded(HttpExchange exchange, byte[] body) {
            this.method = exchange.getRequestMethod();
            this.path = exchange.getRequestURI().toString();
            this.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            this.authorization = exchange.getRequestHeaders().getFirst("Authorization");
            this.accept = exchange.getRequestHeaders().getFirst("Accept");
            this.userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            this.body = body;
        }

        JsonObject json() {
            return JsonParser.parseString(new String(body, UTF8)).getAsJsonObject();
        }
    }

    private final HttpServer server;
    private final int dimension;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<Recorded>());
    private volatile boolean acceptArrays = true;
    private volatile boolean shuffleIndexedEntries;
    private volatile int failStatus;
    private volatile String failBody;
    private volatile String overrideBody;

    FakeEmbeddingServer(int dimension) throws IOException {
        this.dimension = dimension;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/embeddings", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return new ArrayList<Recorded>(requests);
        }
    }

    void stringInputOnly() {
        acceptArrays = false;
    }

    void shuffleIndexedEntries() {
        shuffleIndexedEntries = true;
    }

    void failNext(int status, String body) {
        failStatus = status;
        failBody = body;
    }

    void respondWith(String body) {
        overrideBody = body;
    }

    /** Der Vektor, den der Fake für {@code text} liefert. */
    float[] vectorFor(String text) {
        float[] values = new float[dimension];
        int h = text.hashCode();
        for (int i = 0; i < dimension; i++) {
            h = h * 31 + i;
            values[i] = ((h >>> 8) % 2001 - 1000) / 1000f;
        }
        return values;
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        requests.add(new Recorded(exchange, body));
        if (failStatus != 0) {
            int status = failStatus;
            failStatus = 0;
            send(exchange, status, failBody);
            return;
        }
        if (overrideBody != null) {
            send(exchange, 200, overrideBody);
            return;
        }
        JsonElement input = JsonParser.parseString(new String(body, UTF8)).getAsJsonObject().get("input");
        List<String> texts = new ArrayList<String>();
        if (input.isJsonArray()) {
            if (!acceptArrays) {
                send(exchange, 422, "{\"detail\":\"Input should be a valid string\"}");
                return;
            }
            for (JsonElement element : input.getAsJsonArray()) {
                texts.add(element.getAsString());
            }
        } else {
            texts.add(input.getAsString());
        }
        JsonArray data = new JsonArray();
        for (int i = 0; i < texts.size(); i++) {
            int index = shuffleIndexedEntries ? texts.size() - 1 - i : i;
            JsonObject entry = new JsonObject();
            entry.addProperty("object", "embedding");
            entry.addProperty("index", index);
            JsonArray vector = new JsonArray();
            for (float v : vectorFor(texts.get(index))) {
                vector.add(v);
            }
            entry.add("embedding", vector);
            data.add(entry);
        }
        JsonObject response = new JsonObject();
        response.addProperty("object", "list");
        response.add("data", data);
        response.addProperty("model", "fake");
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 0);
        usage.addProperty("total_tokens", 0);
        response.add("usage", usage);
        send(exchange, 200, response.toString());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(UTF8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

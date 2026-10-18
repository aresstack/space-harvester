package com.aresstack.enterpriseai.chat.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lokaler Fake eines OpenAI-kompatiblen Servers auf Basis von com.sun.net.httpserver (JDK). Nimmt Anfragen
 * auf und spielt eine vorgegebene Antwort ab: Status, Content-Type und Body-Stücke, die einzeln geflusht
 * werden; optional hängt die Antwort danach, bis der Client trennt oder der Server stoppt.
 */
final class FakeOpenAiServer {

    static final Charset UTF_8 = Charset.forName("UTF-8");

    /** Eine aufgezeichnete Anfrage. */
    static final class Recorded {
        final String method;
        final URI uri;
        final com.sun.net.httpserver.Headers headers;
        final byte[] body;

        Recorded(String method, URI uri, com.sun.net.httpserver.Headers headers, byte[] body) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
        }

        String bodyText() {
            return new String(body, UTF_8);
        }

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new ArrayList<Recorded>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch firstPartSent = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile String contentType = "text/event-stream";
    private volatile List<String> parts = new ArrayList<String>();
    private volatile boolean hangAfterParts;
    private volatile String extraHeaderName;
    private volatile String extraHeaderValue;

    FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serve(exchange);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    FakeOpenAiServer respond(int newStatus, String newContentType, String... newParts) {
        this.status = newStatus;
        this.contentType = newContentType;
        this.parts = Arrays.asList(newParts);
        return this;
    }

    FakeOpenAiServer hangAfterParts() {
        this.hangAfterParts = true;
        return this;
    }

    FakeOpenAiServer header(String name, String value) {
        this.extraHeaderName = name;
        this.extraHeaderValue = value;
        return this;
    }

    boolean awaitFirstPart() throws InterruptedException {
        return firstPartSent.await(5, TimeUnit.SECONDS);
    }

    synchronized Recorded lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    synchronized int requestCount() {
        return requests.size();
    }

    void stop() {
        release.countDown();
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void serve(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        synchronized (this) {
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders(), body));
        }
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (extraHeaderName != null) {
            exchange.getResponseHeaders().set(extraHeaderName, extraHeaderValue);
        }
        exchange.sendResponseHeaders(status, 0);
        OutputStream output = exchange.getResponseBody();
        try {
            for (String part : parts) {
                output.write(part.getBytes(UTF_8));
                output.flush();
                firstPartSent.countDown();
            }
            firstPartSent.countDown();
            if (hangAfterParts) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IOException clientGone) {
            // Client hat getrennt (z. B. Cancel)
        } finally {
            try {
                output.close();
            } catch (IOException ignored) {
                // Client bereits weg
            }
            exchange.close();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** Eine SSE-Datenzeile. */
    static String data(String json) {
        return "data: " + json + "\n\n";
    }

    static String deltaChunk(String contentJsonValue, String finishReasonJsonValue) {
        return data("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-intern\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":" + contentJsonValue + "},\"finish_reason\":" + finishReasonJsonValue + "}]}");
    }
}

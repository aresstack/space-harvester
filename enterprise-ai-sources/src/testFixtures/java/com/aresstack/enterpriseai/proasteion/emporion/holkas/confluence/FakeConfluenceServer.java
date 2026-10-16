package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liefert eine {@link FakeConfluence} über echtes HTTP auf 127.0.0.1 aus, damit der produktive
 * {@code UrlConnectionConfluenceTransport} des Adapters gegen sie laufen kann (AP25, Slice D). Eingehende
 * Anfragen an {@link #baseUrl()} werden auf {@link FakeConfluence#BASE} abgebildet; Header (etwa
 * {@code Authorization}) landen unverändert in {@link FakeConfluence#requestHeaders()}.
 */
public final class FakeConfluenceServer implements AutoCloseable {

    private final FakeConfluence confluence;
    private final HttpServer server;

    public FakeConfluenceServer(FakeConfluence confluence) throws IOException {
        this.confluence = confluence;
        // Explizit 127.0.0.1 statt getLoopbackAddress(): Letzteres liefert bei preferIPv6Addresses ::1, die
        // Basis-URL nennt aber immer 127.0.0.1 (Bind- und Advertise-Adresse müssen übereinstimmen).
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serve(exchange);
            }
        });
        server.start();
    }

    /** Basis-URL inklusive Kontextpfad für {@code ConfluenceConfig.builder(baseUrl)} ({@code http}, Test). */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    public FakeConfluence confluence() {
        return confluence;
    }

    private void serve(HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                headers.put(header.getKey(), header.getValue().get(0));
            }
        }
        ConfluenceHttpResponse response;
        try {
            synchronized (confluence) {
                response = confluence.get(mapped, headers, Integer.MAX_VALUE);
            }
        } catch (IOException e) {
            response = new ConfluenceHttpResponse(500, "text/plain", new byte[0]);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

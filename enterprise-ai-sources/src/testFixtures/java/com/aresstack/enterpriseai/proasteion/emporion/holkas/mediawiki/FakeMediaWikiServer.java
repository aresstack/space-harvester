package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Liefert eine {@link FakeMediaWikiTransport Fake-Wiki} über echtes HTTP auf 127.0.0.1 aus, damit der
 * produktive {@code UrlConnectionMediaWikiTransport} des Adapters gegen sie laufen kann (AP25, Slice C). Die
 * Fake-Wiki merkt sich einen Login global; dieser Wrapper bindet ihn wie ein echtes MediaWiki an ein
 * Session-Cookie, so dass nur Anfragen mit dem ausgegebenen Cookie als angemeldet gelten.
 *
 * <p>Herkunft: {@code MediaWikiHttpIntegrationTest} (AP12), als wiederverwendbares Fixture herausgelöst.
 */
public final class FakeMediaWikiServer implements AutoCloseable {

    private final FakeMediaWikiTransport wiki;
    private final HttpServer server;
    private volatile String session;

    public FakeMediaWikiServer(FakeMediaWikiTransport wiki) throws IOException {
        this.wiki = wiki;
        // Explizit 127.0.0.1 statt getLoopbackAddress(): Letzteres liefert bei preferIPv6Addresses ::1, die
        // Basis-URL nennt aber immer 127.0.0.1 (Bind- und Advertise-Adresse müssen übereinstimmen).
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/w/api.php", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serve(exchange);
            }
        });
        server.start();
    }

    /** Die Script-URL der Wiki ({@code .../w}); {@code MediaWikiSiteConfig} hängt {@code /api.php} an. */
    public String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/w";
    }

    public FakeMediaWikiTransport wiki() {
        return wiki;
    }

    private void serve(HttpExchange exchange) throws IOException {
        MediaWikiTransport.Response response;
        synchronized (wiki) {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            boolean knownSession = session != null && ("s=" + session).equals(cookie);
            wiki.sessionValid = knownSession;
            response = "POST".equals(exchange.getRequestMethod())
                    ? wiki.postForm(read(exchange.getRequestBody()))
                    : wiki.get(exchange.getRequestURI().getRawQuery());
            if (wiki.sessionValid && !knownSession) {
                session = UUID.randomUUID().toString();
                exchange.getResponseHeaders().add("Set-Cookie", "s=" + session + "; Path=/; HttpOnly");
            } else if ("POST".equals(exchange.getRequestMethod()) && cookie == null) {
                exchange.getResponseHeaders().add("Set-Cookie", "s=anon; Path=/");
            }
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

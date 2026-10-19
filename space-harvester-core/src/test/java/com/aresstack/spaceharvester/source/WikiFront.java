package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.FakeMediaWikiServer;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.FakeMediaWikiTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Die Fake-Wiki aus den Test-Fixtures der Kopie ({@link FakeMediaWikiServer}) hinter einem eigenen lokalen
 * HTTP(S)-Server: zählt die Anfragen, die ankommen, kann für einzelne Seiten das Lesen verweigern wie ein Wiki mit
 * Leserechten je Seite ({@code permissiondenied} bei {@code action=parse}, nicht in der Suche) und auf Wunsch nur
 * Clients mit Client-Zertifikat annehmen (mTLS). Alles andere geht unverändert an die Fake-Wiki, samt Cookies.
 */
public final class WikiFront implements AutoCloseable {

    private final FakeMediaWikiServer wiki;
    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final Set<String> unreadable = new CopyOnWriteArraySet<>();
    private final boolean https;

    /** Über HTTP. */
    public WikiFront(FakeMediaWikiTransport wiki) throws IOException {
        this(wiki, null, false);
    }

    /** Über HTTPS mit {@code tls}; {@code needClientAuth} verlangt ein Client-Zertifikat. */
    public WikiFront(FakeMediaWikiTransport wiki, SSLContext tls, boolean needClientAuth) throws IOException {
        this.wiki = new FakeMediaWikiServer(wiki);
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
        if (tls == null) {
            server = HttpServer.create(address, 0);
            https = false;
        } else {
            HttpsServer secure = HttpsServer.create(address, 0);
            secure.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                    parameters.setNeedClientAuth(needClientAuth);
                    params.setSSLParameters(parameters);
                }
            });
            server = secure;
            https = true;
        }
        server.createContext("/w/api.php", this::serve);
        server.start();
    }

    /** Die Script-URL ({@code …/w}) mit {@code host} statt 127.0.0.1. */
    public String apiUrl(String host) {
        return (https ? "https" : "http") + "://" + host + ":" + server.getAddress().getPort() + "/w";
    }

    public String apiUrl() {
        return apiUrl("127.0.0.1");
    }

    /** Diese Seiten darf das Konto nicht lesen. */
    public WikiFront unreadable(String... titles) {
        unreadable.addAll(List.of(titles));
        return this;
    }

    /** Abfragen und Formulare, die hier ankamen, in Reihenfolge. */
    public List<String> queries() {
        return queries;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean post = "POST".equals(exchange.getRequestMethod());
            byte[] form = exchange.getRequestBody().readAllBytes();
            String query = post ? new String(form, StandardCharsets.UTF_8) : exchange.getRequestURI().getRawQuery();
            queries.add(query == null ? "" : query);
            String decoded = URLDecoder.decode(query == null ? "" : query, StandardCharsets.UTF_8);
            if (decoded.contains("action=parse") && unreadable.stream().anyMatch(title ->
                    decoded.contains("&page=" + title))) {
                send(exchange, 200, "{\"error\":{\"code\":\"permissiondenied\",\"info\":\"You are not allowed to "
                        + "read this page.\"}}", null);
                return;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(wiki.apiUrl() + "/api.php"
                    + (post ? "" : "?" + query)));
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                request.header("Cookie", cookie);
            }
            if (post) {
                request.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(form));
            }
            HttpResponse<String> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            String body = response.body();
            if (decoded.contains("list=search")) {
                body = withoutUnreadableHits(body);
            }
            send(exchange, response.statusCode(), body, response.headers().firstValue("Set-Cookie").orElse(null));
        }
    }

    /** Die Suche des Wikis zeigt keine Seiten, die das Konto nicht lesen darf. */
    private String withoutUnreadableHits(String body) {
        String filtered = body;
        for (String title : unreadable) {
            filtered = filtered.replaceAll("\\{\"ns\":0,\"title\":\"" + java.util.regex.Pattern.quote(title)
                    + "\"[^}]*},?", "");
        }
        return filtered.replace(",]", "]");
    }

    private static void send(HttpExchange exchange, int status, String body, String setCookie) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        if (setCookie != null) {
            exchange.getResponseHeaders().add("Set-Cookie", setCookie);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        wiki.close();
    }
}

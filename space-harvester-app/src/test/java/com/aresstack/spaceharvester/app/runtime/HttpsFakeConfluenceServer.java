package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Kopie von {@code HttpsFakeConfluenceServer} aus den Tests von space-harvester-core (dort nicht in den testFixtures),
 * ergänzt um {@link #holdUnless(Predicate)}/{@link #release()} für Abbruchtests.
 *
 * Liefert eine {@link FakeConfluence} über HTTPS auf 127.0.0.1 aus; auf Wunsch nur an Clients mit einem vom Server
 * akzeptierten Client-Zertifikat (mTLS).
 */
final class HttpsFakeConfluenceServer implements AutoCloseable {

    private final FakeConfluence confluence;
    private final HttpsServer server;
    private volatile CountDownLatch gate;
    private volatile Predicate<URI> pass = uri -> true;

    HttpsFakeConfluenceServer(FakeConfluence confluence, SSLContext tls, boolean needClientAuth)
            throws IOException {
        this.confluence = confluence;
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(needClientAuth);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/", this::serve);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    /**
     * Hält alle folgenden Anfragen an, die {@code pass} nicht durchlässt, bis {@link #release()} sie freigibt (z. B.
     * um einen Abbruch zu prüfen, während eine synchrone Vorprüfung noch beantwortet wird).
     */
    void holdUnless(Predicate<URI> pass) {
        this.pass = pass;
        gate = new CountDownLatch(1);
    }

    void release() {
        CountDownLatch current = gate;
        gate = null;
        if (current != null) {
            current.countDown();
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        CountDownLatch current = gate;
        if (current != null && !pass.test(exchange.getRequestURI())) {
            try {
                current.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                headers.put(header.getKey(), header.getValue().get(0));
            }
        }
        ConfluenceHttpResponse response;
        synchronized (confluence) {
            response = confluence.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ganzer Adapter über echtes HTTP: produktiver {@code UrlConnectionMediaWikiTransport} gegen einen lokalen
 * JDK-HttpServer, der die Fake-{@code api.php} ausliefert und Logins an ein Session-Cookie bindet.
 */
public class MediaWikiHttpIntegrationTest {

    private final FakeMediaWikiTransport wiki = MediaWikiKnowledgeSourceContractTest.sampleWiki();
    private HttpServer server;
    private volatile String session;

    @Before
    public void start() throws IOException {
        wiki.requiredUser = "indexer";
        wiki.requiredPassword = "pässwört";
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/w/api.php", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // Die Fake-Wiki merkt sich den Login global; hier gilt er nur mit dem ausgegebenen Cookie.
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                wiki.sessionValid = session != null && ("s=" + session).equals(cookie);
                MediaWikiTransport.Response response = "POST".equals(exchange.getRequestMethod())
                        ? wiki.postForm(read(exchange.getRequestBody()))
                        : wiki.get(exchange.getRequestURI().getRawQuery());
                if (wiki.sessionValid && !("s=" + session).equals(cookie)) {
                    session = UUID.randomUUID().toString();
                    exchange.getResponseHeaders().add("Set-Cookie", "s=" + session + "; Path=/; HttpOnly");
                } else if ("POST".equals(exchange.getRequestMethod()) && cookie == null) {
                    exchange.getResponseHeaders().add("Set-Cookie", "s=anon; Path=/");
                }
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                out.close();
            }
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void crawlsAndLoadsALoginProtectedWikiOverHttp() throws Exception {
        MediaWikiKnowledgeSource source = source("pässwört");

        List<KnowledgeResource> resources =
                source.discover(SourceScope.builder().startPoint("Hauptseite").maxDepth(1).maxResources(3).build());
        KnowledgeDocument handbuch = source.load(resources.get(1).id());

        assertEquals(3, resources.size());
        assertEquals("wiki:local/Handbuch", handbuch.id().value());
        assertTrue(handbuch.text().contains("## Installation"));
        assertEquals(1, wiki.logins);
    }

    @Test
    public void wrongPasswordIsAccessDenied() {
        try {
            source("falsch").discover(SourceScope.of("Hauptseite"));
            fail();
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.ACCESS_DENIED, e.kind());
        }
    }

    private MediaWikiKnowledgeSource source(final String password) {
        return new MediaWikiKnowledgeSource(SourceAddress.of("wiki-local"),
                MediaWikiSiteConfig.builder("local", "http://127.0.0.1:" + server.getAddress().getPort() + "/w")
                        .requiresLogin(true).build(),
                new MediaWikiCredentialsProvider() {
                    @Override
                    public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) {
                        return new MediaWikiCredentials("indexer", password.toCharArray());
                    }
                });
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
}

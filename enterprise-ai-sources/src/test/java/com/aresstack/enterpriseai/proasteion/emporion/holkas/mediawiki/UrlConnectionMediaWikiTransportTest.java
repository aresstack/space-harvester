package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Echte HTTP-Grenze gegen einen lokalen JDK-HttpServer: Cookies, Redirects, UTF-8, Fehlerstatus. */
public class UrlConnectionMediaWikiTransportTest {

    private HttpServer server;
    private final List<String> cookiesSeen = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> bodiesSeen = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> urisSeen = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> agentsSeen = Collections.synchronizedList(new ArrayList<String>());
    private UrlConnectionMediaWikiTransport transport;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/w/api.php", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                cookiesSeen.add(exchange.getRequestHeaders().getFirst("Cookie"));
                urisSeen.add(exchange.getRequestURI().toString());
                agentsSeen.add(exchange.getRequestHeaders().getFirst("User-Agent"));
                String body = read(exchange.getRequestBody());
                bodiesSeen.add(body);
                String query = exchange.getRequestURI().getRawQuery();
                if ("POST".equals(exchange.getRequestMethod()) && body.contains("bounce=1")) {
                    exchange.getResponseHeaders().add("Location",
                            "http://localhost:" + server.getAddress().getPort() + "/w/api.php");
                    respond(exchange, 307, "");
                } else if ("POST".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("Set-Cookie", "wikiSession=abc; Path=/");
                    respond(exchange, 200, "{\"ok\":\"ä\"}");
                } else if (query != null && query.contains("moved")) {
                    exchange.getResponseHeaders().add("Location", "/w/api.php?target=1");
                    respond(exchange, 302, "");
                } else if (query != null && query.contains("fail")) {
                    respond(exchange, 503, "busy");
                } else {
                    respond(exchange, 200, "{\"query\":\"" + query + "\"}");
                }
            }
        });
        server.start();
        transport = new UrlConnectionMediaWikiTransport(MediaWikiSiteConfig
                .builder("local", "http://127.0.0.1:" + server.getAddress().getPort() + "/w/").build());
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void postsUtf8FormAndKeepsSessionCookie() throws IOException {
        MediaWikiTransport.Response login = transport.postForm("lgname=" + MediaWikiClient.enc("Jörg"));
        assertEquals(200, login.status());
        assertEquals("{\"ok\":\"ä\"}", login.body());
        assertEquals("lgname=J%C3%B6rg", bodiesSeen.get(0));
        assertNull(cookiesSeen.get(0));

        transport.get("action=query");
        assertEquals("wikiSession=abc", cookiesSeen.get(1));

        transport.resetSession();
        transport.get("action=query");
        assertNull(cookiesSeen.get(2));
    }

    @Test
    public void followsRedirectsWithCookies() throws IOException {
        transport.postForm("x=1");
        MediaWikiTransport.Response response = transport.get("moved=1");
        assertEquals(200, response.status());
        assertTrue(response.body().contains("target=1"));
        assertEquals("wikiSession=abc", cookiesSeen.get(cookiesSeen.size() - 1));
    }

    @Test
    public void returnsErrorStatusWithBody() throws IOException {
        MediaWikiTransport.Response response = transport.get("fail=1");
        assertEquals(503, response.status());
        assertEquals("busy", response.body());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
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

    @Test
    public void refusesToForwardFormDataToAnotherOrigin() {
        try {
            transport.postForm("bounce=1&lgpassword=geheim");
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("another origin"));
        }
        assertEquals(1, bodiesSeen.size());
    }

    @Test
    public void aProxyRouteSendsTheAbsoluteUrlToTheProxy() throws IOException {
        final int port = server.getAddress().getPort();
        HttpRoutePort viaProxy = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(java.net.URI target) {
                return HttpRoute.proxy("127.0.0.1", port, "test");
            }
        };
        UrlConnectionMediaWikiTransport proxied = new UrlConnectionMediaWikiTransport(MediaWikiSiteConfig
                .builder("intern", "http://wiki.intern.invalid/w/").userAgent("EnterpriseAiClient/test").build(),
                viaProxy, null);
        MediaWikiTransport.Response response = proxied.get("action=query");
        assertEquals(200, response.status());
        assertEquals("http://wiki.intern.invalid/w/api.php?action=query", urisSeen.get(urisSeen.size() - 1));
        assertEquals("EnterpriseAiClient/test", agentsSeen.get(agentsSeen.size() - 1));
    }

    @Test
    public void anUnavailableRouteFailsBeforeAnyRequest() {
        HttpRoutePort unavailable = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(java.net.URI target) {
                return HttpRoute.unavailable("pac-download-failed", "HTTP 404");
            }
        };
        UrlConnectionMediaWikiTransport blocked = new UrlConnectionMediaWikiTransport(MediaWikiSiteConfig
                .builder("local", "http://127.0.0.1:" + server.getAddress().getPort() + "/w/").build(),
                unavailable, null);
        try {
            blocked.get("action=query");
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("pac-download-failed"));
        }
        assertTrue(urisSeen.isEmpty());
    }

    @Test
    public void aDirectRouteConnectsWithoutProxy() throws IOException {
        UrlConnectionMediaWikiTransport direct = new UrlConnectionMediaWikiTransport(MediaWikiSiteConfig
                .builder("local", "http://127.0.0.1:" + server.getAddress().getPort() + "/w/").build(),
                HttpRoutePort.direct(), null);
        assertEquals(200, direct.get("action=query").status());
        assertEquals("/w/api.php?action=query", urisSeen.get(urisSeen.size() - 1));
    }

    @Test
    public void redirectRules() throws IOException {
        UrlConnectionMediaWikiTransport.checkRedirect("https://wiki.example/w/api.php",
                "https://WIKI.example:443/w/index.php", true);
        UrlConnectionMediaWikiTransport.checkRedirect("http://wiki.example/w/api.php",
                "https://other.example/w/api.php", false);
        try {
            UrlConnectionMediaWikiTransport.checkRedirect("https://wiki.example/w/api.php",
                    "http://wiki.example/w/api.php", false);
            fail("downgrade must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
        try {
            UrlConnectionMediaWikiTransport.checkRedirect("https://wiki.example/w/api.php",
                    "https://wiki.example:8443/w/api.php", true);
            fail("other port is another origin");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("another origin"));
        }
    }
}

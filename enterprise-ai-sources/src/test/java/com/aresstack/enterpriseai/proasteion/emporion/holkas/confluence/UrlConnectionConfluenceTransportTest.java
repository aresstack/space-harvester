package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Echter HTTP-Weg gegen einen lokalen {@link HttpServer} (ohne Netz nach außen). */
public class UrlConnectionConfluenceTransportTest {

    private HttpServer server;
    private URI base;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<String>();
    private final AtomicReference<String> seenUri = new AtomicReference<String>();
    private final AtomicReference<String> seenCookie = new AtomicReference<String>();
    private final AtomicReference<String> seenUserAgent = new AtomicReference<String>();
    private final UrlConnectionConfluenceTransport transport =
            UrlConnectionConfluenceTransport.builder().proxy(Proxy.NO_PROXY).readTimeoutMillis(5000).build();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenUri.set(exchange.getRequestURI().toString());
            seenUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "application/json", "{\"a\":1}");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://elsewhere.invalid/login");
            respond(exchange, 302, "text/html", "");
        });
        server.createContext("/missing", exchange -> respond(exchange, 404, "application/json", "{\"statusCode\":404}"));
        server.createContext("/big", exchange -> respond(exchange, 200, "text/plain", new String(new char[20000]).replace('\0', 'x')));
        server.createContext("/hop1", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "A=1; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "B=2; HttpOnly");
            exchange.getResponseHeaders().add("Location", "/hop2");
            respond(exchange, 302, "text/html", "");
        });
        server.createContext("/hop2", exchange -> {
            seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Set-Cookie", "A=3; Secure");
            exchange.getResponseHeaders().add("Set-Cookie", "B=x; Max-Age=0");
            exchange.getResponseHeaders().add("Set-Cookie", "C=4");
            respond(exchange, 200, "application/json", "{}");
        });
        server.start();
        base = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void sendsHeadersAndReadsTheBody() throws IOException {
        ConfluenceHttpResponse response = transport.get(base.resolve("/ok"), headers(), 1024);
        assertEquals(200, response.status());
        assertTrue(response.contentType().startsWith("application/json"));
        assertEquals("{\"a\":1}", response.bodyAsUtf8());
        assertEquals("Basic dGVzdA==", seenAuthorization.get());
        assertFalse(response.toString().contains("\"a\""));
    }

    @Test
    public void doesNotFollowRedirects() throws IOException {
        assertEquals(302, transport.get(base.resolve("/redirect"), headers(), 1024).status());
    }

    @Test
    public void errorStatusesAreResponsesWithBody() throws IOException {
        ConfluenceHttpResponse response = transport.get(base.resolve("/missing"), headers(), 1024);
        assertEquals(404, response.status());
        assertEquals("{\"statusCode\":404}", response.bodyAsUtf8());
    }

    @Test
    public void rejectsBodiesAboveTheLimit() {
        try {
            transport.get(base.resolve("/big"), headers(), 1000);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("1000"));
        }
    }

    @Test(expected = IOException.class)
    public void rejectsOtherSchemes() throws IOException {
        transport.get(URI.create("file:///etc/passwd"), headers(), 1000);
    }

    @Test
    public void toStringShowsProxyAndTlsButNoHeaders() {
        assertEquals("UrlConnectionConfluenceTransport[proxy=DIRECT, tls=false]", transport.toString());
    }

    @Test
    public void aProxyRouteSendsTheAbsoluteUrlToTheProxyAndTheUserAgent() throws IOException {
        final int port = server.getAddress().getPort();
        HttpRoutePort viaProxy = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(URI target) {
                return HttpRoute.proxy("127.0.0.1", port, "test");
            }
        };
        UrlConnectionConfluenceTransport proxied = UrlConnectionConfluenceTransport.builder()
                .routes(viaProxy).userAgent("EnterpriseAiClient/test").readTimeoutMillis(5000).build();
        ConfluenceHttpResponse response = proxied.get(URI.create("http://confluence.intern.invalid/ok"), headers(), 1024);
        assertEquals(200, response.status());
        assertEquals("http://confluence.intern.invalid/ok", seenUri.get());
        assertEquals("EnterpriseAiClient/test", seenUserAgent.get());
        assertEquals("UrlConnectionConfluenceTransport[proxy=" + viaProxy + ", tls=false]", proxied.toString());
    }

    @Test
    public void anUnavailableRouteFailsBeforeAnyRequest() {
        HttpRoutePort unavailable = new HttpRoutePort() {
            @Override
            public HttpRoute routeFor(URI target) {
                return HttpRoute.unavailable("pac-download-failed", "HTTP 404");
            }
        };
        UrlConnectionConfluenceTransport blocked = UrlConnectionConfluenceTransport.builder().routes(unavailable).build();
        try {
            blocked.get(base.resolve("/ok"), headers(), 1024);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("pac-download-failed"));
        }
        assertEquals(null, seenUri.get());
    }

    @Test
    public void aDirectRouteTakesPrecedenceOverAFixedProxy() throws IOException {
        UrlConnectionConfluenceTransport direct = UrlConnectionConfluenceTransport.builder()
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)))
                .routes(HttpRoutePort.direct()).readTimeoutMillis(5000).build();
        assertEquals(200, direct.get(base.resolve("/ok"), headers(), 1024).status());
        assertEquals("/ok", seenUri.get());
    }

    @Test
    public void cookiesAreCollectedOverSameHostRedirectsLaterOnesWinAndDeletionsDrop() throws IOException {
        ConfluenceHttpResponse response = transport.get(base.resolve("/hop1"), headers(), 1024);

        assertEquals(200, response.status());
        // Reihenfolge mehrerer Set-Cookie-Header gibt HttpURLConnection nicht verlässlich wieder
        assertEquals(new java.util.TreeSet<String>(java.util.Arrays.asList("A=3", "C=4")),
                new java.util.TreeSet<String>(response.cookies()));
        // wie ein Browser: was ein Schritt setzt, geht beim nächsten mit (die Einmalcode-Sitzung des
        // Zwei-Faktor-Plugins entsteht vor der Weiterleitung auf seine Seite)
        assertEquals(new java.util.TreeSet<String>(java.util.Arrays.asList("A=1", "B=2")),
                new java.util.TreeSet<String>(java.util.Arrays.asList(seenCookie.get().split("; "))));
    }

    @Test
    public void aFormIsPostedAndAfterA303TheRedirectIsAGetWithTheNewCookie() throws IOException {
        final AtomicReference<String> posted = new AtomicReference<String>();
        final AtomicReference<String> postCookie = new AtomicReference<String>();
        final AtomicReference<String> contentType = new AtomicReference<String>();
        final AtomicReference<String> afterMethod = new AtomicReference<String>();
        server.createContext("/form", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            postCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            posted.set(new String(readAll(exchange.getRequestBody()), java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Set-Cookie", "S=neu; Path=/");
            exchange.getResponseHeaders().add("Location", "/after");
            respond(exchange, 303, "text/html", "");
        });
        server.createContext("/after", exchange -> {
            afterMethod.set(exchange.getRequestMethod());
            seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, 200, "text/html", "<html>ok</html>");
        });
        Map<String, String> headers = new LinkedHashMap<String, String>(headers());
        headers.put("Cookie", "S=alt; T=1");

        ConfluenceHttpResponse response = transport.postForm(base.resolve("/form"), headers,
                "a=1&code=42".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1024);

        assertEquals(200, response.status());
        assertEquals("a=1&code=42", posted.get());
        assertTrue(contentType.get(), contentType.get().startsWith("application/x-www-form-urlencoded"));
        assertEquals("S=alt; T=1", postCookie.get());
        assertEquals("GET", afterMethod.get());
        assertEquals("S=neu; T=1".length(), seenCookie.get().length());
        assertTrue(seenCookie.get(), seenCookie.get().contains("S=neu") && seenCookie.get().contains("T=1"));
        assertEquals(java.util.Collections.singletonList("S=neu"), response.cookies());
        assertEquals(base.resolve("/after"), response.uri());
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Test
    public void theSetCookieParserKeepsNameAndValueOnlyAndTheLastOfEachName() {
        Map<String, java.util.List<String>> fields = new LinkedHashMap<String, java.util.List<String>>();
        fields.put(null, Collections.singletonList("HTTP/1.1 200 OK"));
        fields.put("set-cookie", java.util.Arrays.asList("S=1; Path=/; Secure", "S=2", "D=y; Max-Age = 0",
                "kaputt", "=ohneName", "E=; Path=/"));

        assertEquals(java.util.Arrays.asList("S=2", "D=", "E="), UrlConnectionConfluenceTransport.setCookies(fields));
    }

    @Test
    public void theAnonymousGetUsesTheSameRouteWithoutTheClientCertificate() throws IOException {
        ConfluenceHttpResponse response = transport.getAnonymous(base.resolve("/ok"), headers(), 1024);

        assertEquals(200, response.status());
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Basic dGVzdA==");
        return Collections.unmodifiableMap(headers);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

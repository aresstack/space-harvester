package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Vertragstest für {@link ConfluenceAsyncHttpTransport} gegen einen lokalen HTTP-Server (ohne Netz nach außen).
 * Eine Implementierung erbt davon und liefert in {@link #transport()} einen direkt (ohne Proxy) verbindenden
 * Transport mit höchstens 5 s Lese-Timeout. Proxy-Route und Client-Zertifikat prüft die Implementierung selbst,
 * weil sie dafür ihre eigene Konfiguration braucht.
 */
public abstract class ConfluenceAsyncHttpTransportContract {

    private static final long WAIT_SECONDS = 10;

    /** Kein UTF-8: BOM, ungültiges Byte, NUL und CRLF müssen unverändert ankommen. */
    private static final byte[] RAW = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '"', 'a', '"', ':', (byte) 0xFF,
            0, '\r', '\n', '}'};

    private HttpServer server;
    private URI base;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<String> seenAuthorization = new AtomicReference<String>();
    private final AtomicReference<String> seenCookie = new AtomicReference<String>();

    /** Der zu prüfende Transport; wird je Test neu geholt. */
    protected abstract ConfluenceAsyncHttpTransport transport();

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/ok", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/raw", exchange -> respond(exchange, 200, "application/json", RAW));
        server.createContext("/slow", exchange -> {
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/missing", exchange ->
                respond(exchange, 404, "application/json", "{\"statusCode\":404}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/big", exchange -> respond(exchange, 200, "text/plain", new byte[20000]));
        server.createContext("/foreign", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://elsewhere.invalid/login");
            respond(exchange, 302, "text/html", new byte[0]);
        });
        server.createContext("/hop1", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "A=1; Path=/");
            exchange.getResponseHeaders().add("Location", "/hop2");
            respond(exchange, 302, "text/html", new byte[0]);
        });
        server.createContext("/hop2", exchange -> {
            seenCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Set-Cookie", "B=2");
            respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        base = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    @After
    public void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    public void sendsTheHeadersAndReadsStatusTypeAndBody() throws Exception {
        ConfluenceHttpResponse response = await(transport().getAsync(base.resolve("/ok"), headers(), 1024));

        assertEquals(200, response.status());
        assertTrue(response.contentType().startsWith("application/json"));
        assertEquals("{\"a\":1}", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("Bearer token", seenAuthorization.get());
    }

    @Test
    public void theBodyArrivesByteForByte() throws Exception {
        assertArrayEquals(RAW, await(transport().getAsync(base.resolve("/raw"), headers(), 1024)).body());
    }

    @Test
    public void returnsBeforeTheServerAnswers() throws Exception {
        CompletionStage<ConfluenceHttpResponse> stage = transport().getAsync(base.resolve("/slow"), headers(), 1024);

        assertFalse("die Stufe ist fertig, bevor der Server geantwortet hat",
                stage.toCompletableFuture().isDone());
        release.countDown();
        assertEquals(200, await(stage).status());
    }

    @Test
    public void errorStatusesAreResponsesWithBody() throws Exception {
        ConfluenceHttpResponse response = await(transport().getAsync(base.resolve("/missing"), headers(), 1024));

        assertEquals(404, response.status());
        assertEquals("{\"statusCode\":404}", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void aBodyAboveTheLimitFailsAsTooLarge() throws Exception {
        Throwable failure = failure(transport().getAsync(base.resolve("/big"), headers(), 1024));

        assertTrue("erwartet ConfluenceResponseTooLargeException, war " + failure,
                failure instanceof ConfluenceResponseTooLargeException);
        assertEquals(1024, ((ConfluenceResponseTooLargeException) failure).maxBytes());
    }

    @Test
    public void aRedirectToAnotherHostIsReturnedNotFollowed() throws Exception {
        ConfluenceHttpResponse response = await(transport().getAsync(base.resolve("/foreign"), headers(), 1024));

        assertEquals(302, response.status());
        assertEquals("http://elsewhere.invalid/login", response.location());
    }

    @Test
    public void sameHostRedirectsAreFollowedWithTheCookiesSetOnTheWay() throws Exception {
        ConfluenceHttpResponse response = await(transport().getAsync(base.resolve("/hop1"), headers(), 1024));

        assertEquals(200, response.status());
        assertEquals("A=1", seenCookie.get());
        assertEquals(new TreeSet<String>(Arrays.asList("A=1", "B=2")), new TreeSet<String>(response.cookies()));
        assertEquals(base.resolve("/hop2"), response.uri());
        assertEquals(Collections.singletonList(base.resolve("/hop2").toString()), response.redirects());
    }

    @Test
    public void theAnonymousGetReadsTheSameResponse() throws Exception {
        ConfluenceHttpResponse response = await(transport().getAnonymousAsync(base.resolve("/ok"),
                Collections.<String, String>emptyMap(), 1024));

        assertEquals(200, response.status());
        assertEquals("{\"a\":1}", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void otherSchemesFailWithAnIOException() throws Exception {
        assertIOException(failure(transport().getAsync(URI.create("ftp://localhost/x"), headers(), 1024)));
    }

    @Test
    public void anUnreachableServerFailsWithAnIOException() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        URI unreachable = URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + closedPort
                + "/ok");

        assertIOException(failure(transport().getAsync(unreachable, headers(), 1024)));
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer token");
        headers.put("Accept", "application/json");
        return Collections.unmodifiableMap(headers);
    }

    private static ConfluenceHttpResponse await(CompletionStage<ConfluenceHttpResponse> stage) throws Exception {
        assertNotNull("die Methode lieferte keine Stufe", stage);
        try {
            return stage.toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("Antwort erwartet, war " + unwrap(e), e);
        }
    }

    private static Throwable failure(CompletionStage<ConfluenceHttpResponse> stage) throws Exception {
        assertNotNull("die Methode lieferte keine Stufe", stage);
        CompletableFuture<ConfluenceHttpResponse> future = stage.toCompletableFuture();
        try {
            ConfluenceHttpResponse response = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            fail("Fehler erwartet, war Status " + response.status());
            return null;
        } catch (ExecutionException e) {
            return unwrap(e);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void assertIOException(Throwable failure) {
        assertTrue("erwartet IOException, war " + failure, failure instanceof IOException);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}

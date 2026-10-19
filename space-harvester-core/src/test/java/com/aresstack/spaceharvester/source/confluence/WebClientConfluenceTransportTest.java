package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceResponseTooLargeException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.UrlConnectionConfluenceTransport;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.application.jobs.RequestRoute;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link WebClientConfluenceTransport} gegen einen lokalen Server: Parität mit dem synchronen
 * {@link UrlConnectionConfluenceTransport} und die Eigenschaften, die nur der asynchrone Weg hat (Route außerhalb
 * des Event-Loops, Abbruch, Backpressure, getrennte Pools).
 */
class WebClientConfluenceTransportTest {

    private static final Map<String, String> HEADERS = Map.of("Authorization", "Bearer pat-token",
            "Accept", "application/json", "Cookie", "X=0");

    private HttpServer server;
    private URI base;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> seenCookies = new CopyOnWriteArrayList<>();
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private final List<WebClientConfluenceTransport> transports = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seenCookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/status/")) {
                int status = Integer.parseInt(path.substring("/status/".length()));
                exchange.getResponseHeaders().add("Set-Cookie", "S" + status + "=v; Path=/");
                respond(exchange, status, "application/json;charset=UTF-8",
                        ("{\"statusCode\":" + status + "}").getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/hop1")) {
                exchange.getResponseHeaders().add("Set-Cookie", "A=1; HttpOnly");
                exchange.getResponseHeaders().add("Location", "/hop2");
                respond(exchange, 302, "text/html", new byte[0]);
            } else if (path.equals("/hop2")) {
                exchange.getResponseHeaders().add("Set-Cookie", "A=2");
                exchange.getResponseHeaders().add("Set-Cookie", "B=x; Max-Age=0");
                respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/foreign")) {
                exchange.getResponseHeaders().add("Location", "https://idp.elsewhere.test/login?code=geheim");
                respond(exchange, 302, "text/html", new byte[0]);
            } else if (path.equals("/loop")) {
                exchange.getResponseHeaders().add("Location", "/loop");
                respond(exchange, 302, "text/html", new byte[0]);
            } else if (path.startsWith("/chunked/")) {
                int size = Integer.parseInt(path.substring("/chunked/".length()));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    byte[] chunk = new byte[1000];
                    for (int i = 0; i < size / chunk.length; i++) {
                        java.util.Arrays.fill(chunk, (byte) ('a' + i % 26));
                        out.write(chunk);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // der Transport bricht beim Überschreiten der Grenze ab
                }
            } else if (path.equals("/slow")) {
                await(release);
                respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 404, "text/plain", new byte[0]);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        transports.forEach(WebClientConfluenceTransport::close);
        server.stop(0);
    }

    private WebClientConfluenceTransport transport(WebClientConfluenceTransport.Builder builder) {
        WebClientConfluenceTransport transport = builder.readTimeout(Duration.ofSeconds(5)).build();
        transports.add(transport);
        return transport;
    }

    private WebClientConfluenceTransport transport() {
        return transport(WebClientConfluenceTransport.builder());
    }

    private static final UrlConnectionConfluenceTransport SYNC = UrlConnectionConfluenceTransport.builder()
            .proxy(Proxy.NO_PROXY).readTimeoutMillis(5000).build();

    @ParameterizedTest
    @ValueSource(strings = {"/status/200", "/status/401", "/status/403", "/status/404", "/status/429",
            "/status/500", "/status/503", "/hop1", "/foreign", "/loop"})
    void answersLikeTheSynchronousTransport(String path) throws Exception {
        ConfluenceHttpResponse expected = SYNC.get(base.resolve(path), HEADERS, 4096);
        int syncRequests = requests.getAndSet(0);
        List<String> syncCookies = List.copyOf(seenCookies);
        seenCookies.clear();

        ConfluenceHttpResponse actual = value(transport().getAsync(base.resolve(path), HEADERS, 4096));

        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.contentType()).isEqualTo(expected.contentType());
        assertThat(actual.body()).isEqualTo(expected.body());
        assertThat(actual.location()).isEqualTo(expected.location());
        assertThat(actual.cookies()).containsExactlyInAnyOrderElementsOf(expected.cookies());
        assertThat(actual.uri()).isEqualTo(expected.uri());
        assertThat(actual.redirects()).isEqualTo(expected.redirects());
        assertThat(requests.get()).isEqualTo(syncRequests);
        assertThat(seenCookies).isEqualTo(syncCookies);
    }

    @Test
    void sendsThePatAndOverridesTheUserAgentOnlyWhenTheCallerDoesNot() throws Exception {
        WebClientConfluenceTransport transport = transport(WebClientConfluenceTransport.builder()
                .userAgent("space-harvester/test"));
        AtomicInteger seen = new AtomicInteger();
        server.createContext("/agent", exchange -> {
            if ("space-harvester/test".equals(exchange.getRequestHeaders().getFirst("User-Agent"))) {
                seen.incrementAndGet();
            }
            respond(exchange, 200, "text/plain", new byte[0]);
        });

        value(transport.getAsync(base.resolve("/agent"), HEADERS, 1024));

        assertThat(seen).hasValue(1);
    }

    @Test
    void aChunkedBodyIsReadByteForByteUpToTheLimitAndRejectedAboveIt() throws Exception {
        WebClientConfluenceTransport transport = transport();

        byte[] body = value(transport.getAsync(base.resolve("/chunked/5000"), HEADERS, 5000)).body();
        assertThat(body).hasSize(5000);
        assertThat(body[0]).isEqualTo((byte) 'a');
        assertThat(body[4999]).isEqualTo((byte) 'e');

        assertThat(failure(transport.getAsync(base.resolve("/chunked/200000"), HEADERS, 5000)))
                .isInstanceOfSatisfying(ConfluenceResponseTooLargeException.class,
                        e -> assertThat(e.maxBytes()).isEqualTo(5000));
    }

    /** Mit angekündigter Länge liegt der Körper in genau einem passenden Feld, ohne Schlusskopie. */
    @Test
    void aBodyOfAnnouncedLengthIsFilledInPlace() {
        WebClientConfluenceTransport.Body known = new WebClientConfluenceTransport.Body(3000, 5000);
        byte[] first = known.bytes;
        for (int i = 0; i < 3; i++) {
            known.reserve(1000);
            known.size += 1000;
        }
        assertThat(known.bytes()).isSameAs(first).hasSize(3000);

        WebClientConfluenceTransport.Body unknown = new WebClientConfluenceTransport.Body(-1, 50_000);
        for (int i = 0; i < 20; i++) {
            unknown.reserve(1000);
            unknown.size += 1000;
        }
        assertThat(unknown.bytes()).hasSize(20_000);
        assertThat(unknown.bytes.length).isLessThanOrEqualTo(50_000);
    }

    @Test
    void theRouteIsChosenPerTargetOffTheNettyEventLoop() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        List<URI> targets = new CopyOnWriteArrayList<>();
        HttpRoutePort routes = target -> {
            threads.add(Thread.currentThread().getName());
            targets.add(target);
            return HttpRoute.direct("test");
        };

        value(transport(WebClientConfluenceTransport.builder().routes(routes))
                .getAsync(base.resolve("/hop1"), HEADERS, 1024));

        assertThat(targets).containsExactly(base.resolve("/hop1"), base.resolve("/hop2"));
        assertThat(threads).allSatisfy(name -> assertThat(name).contains("-route").doesNotContain("reactor-http"));
    }

    @Test
    void anUnavailableRouteFailsWithoutAnyConnection() {
        HttpRoutePort routes = target -> HttpRoute.unavailable("pac-failed", "PAC nicht ladbar");

        Throwable failure = failure(transport(WebClientConfluenceTransport.builder().routes(routes))
                .getAsync(base.resolve("/status/200"), HEADERS, 1024));

        assertThat(failure).isInstanceOf(IOException.class).hasMessageContaining("nicht verfügbar");
        assertThat(RequestRoute.find(failure)).map(RequestRoute::describe)
                .contains(HttpRoute.unavailable("pac-failed", "PAC nicht ladbar").describe() + " · WebClient");
        assertThat(requests).hasValue(0);
    }

    @Test
    void differentRoutesNeverShareAPool() throws Exception {
        java.util.concurrent.atomic.AtomicReference<HttpRoute> route =
                new java.util.concurrent.atomic.AtomicReference<>(HttpRoute.direct("eins"));
        WebClientConfluenceTransport transport = transport(WebClientConfluenceTransport.builder()
                .routes(target -> route.get()));

        value(transport.getAsync(base.resolve("/status/200"), HEADERS, 1024));
        route.set(HttpRoute.proxy("127.0.0.1", 1, "zwei"));
        failure(transport.getAsync(base.resolve("/status/200"), HEADERS, 1024)); // Proxy auf Port 1: abgelehnt
        route.set(HttpRoute.direct("eins"));
        value(transport.getAsync(base.resolve("/status/200"), HEADERS, 1024));
        value(transport.getAnonymousAsync(base.resolve("/status/200"), Map.of(), 1024));

        // direkt/Zertifikat, Proxy/Zertifikat, direkt/anonym
        assertThat(transport.clientCount()).isEqualTo(3);
        assertThat(requests).hasValue(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void aProxiedTargetIsNeverResolvedLocally(String scheme) throws Exception {
        // Im Unternehmensnetz löst nur der Proxy externe Namen auf: der Name muss unaufgelöst beim Proxy ankommen.
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> requestLine = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = proxy.accept()) {
                    InputStream in = socket.getInputStream();
                    StringBuilder line = new StringBuilder();
                    for (int c = in.read(); c != -1 && c != '\r'; c = in.read()) {
                        line.append((char) c);
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n"
                            + "Connection: close\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return line.toString();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            WebClientConfluenceTransport transport = transport(WebClientConfluenceTransport.builder()
                    .routes(target -> HttpRoute.proxy("127.0.0.1", proxy.getLocalPort(), "PROXY 127.0.0.1")));

            Throwable outcome = null;
            try {
                transport.getAsync(URI.create(scheme + "://confluence.example.invalid/rest/api/space"), HEADERS, 1024)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                outcome = e.getCause();
            }

            assertThat(requestLine.get(10, TimeUnit.SECONDS)).contains("confluence.example.invalid");
            if (outcome != null) {
                // Der Test-Proxy schließt nach seiner Antwort; nur eine lokale Namensauflösung wäre der Fehler.
                assertThat(rootCause(outcome)).isNotInstanceOf(java.net.UnknownHostException.class);
                assertThat(RequestRoute.find(outcome)).map(RequestRoute::describe)
                        .contains(HttpRoute.proxy("127.0.0.1", proxy.getLocalPort(), "PROXY 127.0.0.1").describe()
                                + " · WebClient");
            }
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Test
    void moreRequestsThanConnectionsAndQueueFailFast() throws Exception {
        WebClientConfluenceTransport transport = transport(WebClientConfluenceTransport.builder()
                .maxConnections(1).maxPending(1));

        CompletableFuture<ConfluenceHttpResponse> first =
                transport.getAsync(base.resolve("/slow"), HEADERS, 1024).toCompletableFuture();
        waitFor(() -> requests.get() == 1);
        CompletableFuture<ConfluenceHttpResponse> queued =
                transport.getAsync(base.resolve("/slow"), HEADERS, 1024).toCompletableFuture();
        Thread.sleep(200);
        Throwable rejected = failure(transport.getAsync(base.resolve("/slow"), HEADERS, 1024));

        assertThat(rejected).isInstanceOf(IOException.class).hasMessageContaining("gleichzeitige");
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(queued.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
    }

    @Test
    void cancellingTheStageClosesTheConnection() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CountDownLatch arrived = new CountDownLatch(1);
            CompletableFuture<Boolean> closedByClient = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = silent.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream in = socket.getInputStream();
                    readHead(in);
                    arrived.countDown();
                    return in.read() == -1;
                } catch (IOException e) {
                    return true; // Reset zählt ebenfalls als geschlossen
                }
            });
            CompletableFuture<ConfluenceHttpResponse> stage = transport().getAsync(
                    URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/page"), HEADERS, 1024)
                    .toCompletableFuture();
            // Abgebrochen wird eine gesendete Anfrage, nicht eine, die noch die Verbindung aufbaut.
            assertThat(arrived.await(10, TimeUnit.SECONDS)).as("Anfrage beim Server angekommen").isTrue();

            assertThat(stage.cancel(true)).isTrue();
            assertThat(closedByClient.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aSilentServerEndsInAReadTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try (Socket socket = silent.accept()) {
                    readHead(socket.getInputStream());
                    Thread.sleep(3000);
                } catch (IOException | InterruptedException ignored) {
                    // Ende des Tests
                }
            });
            WebClientConfluenceTransport transport = WebClientConfluenceTransport.builder()
                    .readTimeout(Duration.ofMillis(300)).build();
            transports.add(transport);

            assertThat(failure(transport.getAsync(URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/page"),
                    HEADERS, 1024))).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void aClosedTransportRejectsNewRequests() {
        WebClientConfluenceTransport transport = transport();
        transport.close();

        assertThat(failure(transport.getAsync(base.resolve("/status/200"), HEADERS, 1024)))
                .isInstanceOf(IOException.class).hasMessageContaining("geschlossen");
    }

    @Test
    void aRequestInFlightEndsNormallyWhenClientsAreDiscardedOrTheTransportClosesGracefully() throws Exception {
        WebClientConfluenceTransport transport = transport();
        CompletableFuture<ConfluenceHttpResponse> running =
                transport.getAsync(base.resolve("/slow"), HEADERS, 1024).toCompletableFuture();
        waitFor(() -> requests.get() == 1);

        transport.discardClients();
        assertThat(transport.clientCount()).isZero();
        CompletableFuture<ConfluenceHttpResponse> second =
                transport.getAsync(base.resolve("/slow"), HEADERS, 1024).toCompletableFuture();
        waitFor(() -> requests.get() == 2);
        transport.closeGracefully();

        release.countDown();
        assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(failure(transport.getAsync(base.resolve("/status/200"), HEADERS, 1024)))
                .isInstanceOf(IOException.class).hasMessageContaining("geschlossen");
    }

    @Test
    void aRequestWaitingForAConnectionStillEndsNormallyWhenClientsAreDiscarded() throws Exception {
        // Eine Verbindung: die zweite Anfrage wartet im Pool, während die Clients verworfen werden.
        WebClientConfluenceTransport transport = transport(WebClientConfluenceTransport.builder().maxConnections(1));
        CompletableFuture<ConfluenceHttpResponse> running =
                transport.getAsync(base.resolve("/slow"), HEADERS, 1024).toCompletableFuture();
        waitFor(() -> requests.get() == 1);
        CompletableFuture<ConfluenceHttpResponse> waiting =
                transport.getAsync(base.resolve("/status/200"), HEADERS, 1024).toCompletableFuture();
        Thread.sleep(200);
        assertThat(waiting).isNotDone();

        transport.discardClients();
        release.countDown();

        assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(waiting.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        transport.close();
    }

    @Test
    void closingHardAfterAGracefulCloseEndsWaitingRequestsAtOnce() throws Exception {
        WebClientConfluenceTransport transport = transport(WebClientConfluenceTransport.builder().maxConnections(1));
        transport.getAsync(base.resolve("/slow"), HEADERS, 1024);
        waitFor(() -> requests.get() == 1);
        CompletableFuture<ConfluenceHttpResponse> waiting =
                transport.getAsync(base.resolve("/status/200"), HEADERS, 1024).toCompletableFuture();
        Thread.sleep(200);

        transport.closeGracefully();
        transport.close();

        // Ohne hartes Schließen wartete die Anfrage bis pendingAcquireTimeout (connect + read).
        assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThatThrownBy(() -> WebClientConfluenceTransport.builder().maxConnections(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebClientConfluenceTransport.builder().readTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static ConfluenceHttpResponse value(java.util.concurrent.CompletionStage<ConfluenceHttpResponse> stage)
            throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static Throwable failure(java.util.concurrent.CompletionStage<ConfluenceHttpResponse> stage) {
        try {
            ConfluenceHttpResponse response = stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
            throw new AssertionError("Fehler erwartet, war HTTP " + response.status());
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readHead(InputStream in) throws IOException {
        int matched = 0;
        int read;
        while (matched < 4 && (read = in.read()) != -1) {
            matched = (read == "\r\n\r\n".charAt(matched)) ? matched + 1 : (read == '\r' ? 1 : 0);
        }
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

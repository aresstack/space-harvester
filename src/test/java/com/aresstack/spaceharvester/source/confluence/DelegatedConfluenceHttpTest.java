package com.aresstack.spaceharvester.source.confluence;

import static com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransportTest.failure;
import static com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransportTest.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Delegierte Aufrufe mit dem Token des Nutzers (#24) über den asynchronen Transport (#47). */
class DelegatedConfluenceHttpTest {

    private static final String TOKEN = "nutzer-token-123";

    private HttpServer server;
    private URI base;
    private DelegatedConfluenceHttp http;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Map<String, List<String>>> seen = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            seen.add(Map.copyOf(exchange.getRequestHeaders()));
            paths.add(exchange.getRequestURI().toString());
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            byte[] body = "{\"type\":\"known\",\"username\":\"angelo\"}".getBytes(StandardCharsets.UTF_8);
            if (path.endsWith("/moved")) {
                exchange.getResponseHeaders().add("Location", "/confluence/rest/api/user/current");
                status = 302;
                body = new byte[0];
            } else if (path.endsWith("/slow")) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (path.endsWith("/denied")) {
                status = 401;
                body = new byte[0];
            }
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=sitzung");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/confluence");
        http = new DelegatedConfluenceHttp(base, WebClientConfluenceTransport.builder().userAgent("space-harvester/test")
                .build(), Duration.ofMillis(500));
    }

    @AfterEach
    void stop() {
        release.countDown();
        http.close();
        server.stop(0);
    }

    @Test
    void sendsOnlyTheUserTokenAndAcceptToThePathUnderTheSource() throws Exception {
        ConfluenceHttpResponse response = value(http.get("/rest/api/user/current", TOKEN));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyAsUtf8()).contains("angelo");
        assertThat(paths).containsExactly("/confluence/rest/api/user/current");
        Map<String, List<String>> headers = seen.get(0);
        assertThat(headers.get("Authorization")).containsExactly("Bearer " + TOKEN);
        assertThat(headers.get("Accept")).containsExactly("application/json");
        assertThat(headers).doesNotContainKey("Cookie");
    }

    @Test
    void redirectsAreReturnedNotFollowedAndCookiesAreNotReused() throws Exception {
        ConfluenceHttpResponse moved = value(http.get("/rest/api/content/1/moved", TOKEN));
        value(http.get("/rest/api/space/DEV", TOKEN));

        assertThat(moved.status()).isEqualTo(302);
        assertThat(moved.location()).isEqualTo("/confluence/rest/api/user/current");
        assertThat(paths).containsExactly("/confluence/rest/api/content/1/moved", "/confluence/rest/api/space/DEV");
        assertThat(seen).allSatisfy(headers -> assertThat(headers).doesNotContainKey("Cookie"));
    }

    @Test
    void statusCodesAreAnswersForTheCaller() throws Exception {
        assertThat(value(http.get("/rest/api/user/denied", TOKEN)).status()).isEqualTo(401);
    }

    @Test
    void theTimeLimitAbortsAndNeverShowsTheToken() {
        Throwable failure = failure(http.get("/rest/api/slow", TOKEN));

        assertThat(failure).isInstanceOf(SocketTimeoutException.class);
        assertThat(String.valueOf(failure.getMessage())).doesNotContain(TOKEN);
    }

    @Test
    void pathsOutsideTheSourceAreRejectedBeforeAnyRequest() {
        for (String path : List.of("http://evil.test/x", "//evil.test/x", "rest/api/space", "/../other",
                "/rest/../../other", "/rest/api#x", "@evil.test/x", "/rest//api/space", "/rest/api/..",
                "/rest/./api", "/rest\\api", "/rest/%2e%2e/other", "/rest/api%2Fspace")) {
            assertThatThrownBy(() -> http.get(path, TOKEN)).as(path).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(TOKEN);
        }
        assertThat(paths).isEmpty();
    }

    @Test
    void unusableTokensAreRejected() {
        for (String token : new String[] {null, " ", "a\r\nX-Injected: 1", "mit leerzeichen"}) {
            assertThatThrownBy(() -> http.get("/rest/api/user/current", token))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(paths).isEmpty();
    }

    @Test
    void toStringNamesOnlyTheSource() {
        assertThat(http.toString()).isEqualTo("DelegatedConfluenceHttp[" + base + "]");
    }
}

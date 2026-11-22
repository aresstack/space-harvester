package com.aresstack.spaceharvester.deck.betaview.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Sitzungs-Cookies gehen nur dorthin, wofür sie gesetzt wurden – nie an einen fremden Host einer Weiterleitung. */
class BetaViewCookiesTest {

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<String> foreignCookies = new CopyOnWriteArrayList<>();
    private final List<String> ownCookies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(server -> server.stop(0));
    }

    private HttpServer server() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servers.add(server);
        return server;
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void aRedirectToAnotherHostGetsNoSessionCookie() throws Exception {
        HttpServer foreign = server();
        foreign.createContext("/", exchange -> {
            foreignCookies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            answer(exchange, "fremd");
        });
        foreign.start();
        HttpServer own = server();
        own.createContext("/betaview/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            ownCookies.add(path + " " + exchange.getRequestHeaders().getFirst("Cookie"));
            if (path.endsWith("start")) {
                exchange.getResponseHeaders().add("Set-Cookie", "SID=geheim; Path=/betaview; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie", "NUR_TLS=x; Path=/betaview; Secure");
                exchange.getResponseHeaders().add("Set-Cookie", "ANDERER_PFAD=y; Path=/admin");
                answer(exchange, "ok");
            } else if (path.endsWith("weiter")) {
                // Anderer Hostname (localhost statt 127.0.0.1) = fremder Host für Cookies.
                exchange.getResponseHeaders().add("Location",
                        "http://localhost:" + foreign.getAddress().getPort() + "/ziel");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            } else {
                answer(exchange, "seite");
            }
        });
        own.start();
        URI base = URI.create("http://127.0.0.1:" + own.getAddress().getPort() + "/betaview/");
        BetaViewHttp http = new BetaViewHttp(HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NEVER).build(), base, Duration.ofSeconds(10));

        http.getText("start");
        http.getText("seite");
        assertThat(ownCookies).last().asString().contains("SID=geheim")
                .doesNotContain("NUR_TLS").doesNotContain("ANDERER_PFAD");

        assertThat(http.getText("weiter")).isEqualTo("fremd");
        assertThat(foreignCookies).containsExactly("null");
        assertThat(http.cookieLine(URI.create("https://127.0.0.1/betaview/x"))).contains("SID=geheim")
                .contains("NUR_TLS=x");
        assertThat(http.cookieLine(URI.create("http://127.0.0.1/admin/x"))).isEqualTo("ANDERER_PFAD=y");
    }
}

package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

final class RoutedWebDavHttpProxyTest {

    @TempDir
    Path dir;

    private HttpsServer server;
    private TestProxy proxy;
    private TestCertificates certificates;

    @BeforeEach
    void start() throws Exception {
        certificates = new TestCertificates(dir);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(certificates.serverContext()) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                parameters.setSSLParameters(ssl);
            }
        });
        server.createContext("/sec/bscw.cgi/123", this::respond);
        server.start();
        proxy = new TestProxy().requireBasic("proxy-user", "proxy-secret");
    }

    @AfterEach
    void stop() throws Exception {
        proxy.close();
        server.stop(0);
    }

    @Test
    void sendsWebDavMethodThroughAuthenticatedHttpsProxy() throws Exception {
        HttpRoutePort route = target -> HttpRoute.proxy("127.0.0.1", proxy.port(), "test");
        RoutedWebDavHttp client = new RoutedWebDavHttp(route, credentials(),
                certificates.harvesterTrustManager(), 4096);

        RoutedWebDavHttp.Result response = client.exchange(target(), "PROPFIND",
                Map.of("Depth", "1", "Authorization", "Basic ZmFrZTpzZWNyZXQ="));

        assertThat(response.status()).isEqualTo(207);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("multistatus");
        assertThat(proxy.requestLines()).anySatisfy(line -> assertThat(line).startsWith("CONNECT "));
        assertThat(proxy.headerBlocks()).anySatisfy(head -> assertThat(head)
                .containsIgnoringCase("Proxy-Authorization: Basic "));
    }

    @Test
    void directRouteBypassesTheConfiguredProxy() throws Exception {
        HttpRoutePort route = target -> HttpRoute.direct("test");
        RoutedWebDavHttp client = new RoutedWebDavHttp(route, credentials(),
                certificates.harvesterTrustManager(), 4096);

        RoutedWebDavHttp.Result response = client.exchange(target(), "PROPFIND",
                Map.of("Depth", "0", "Authorization", "Basic ZmFrZTpzZWNyZXQ="));

        assertThat(response.status()).isEqualTo(207);
        assertThat(proxy.requestLines()).isEmpty();
    }

    private ProxyCredentials credentials() {
        return ProxyCredentials.of(new ProxySettings.Authentication("proxy-user",
                "proxy-secret".toCharArray()), null);
    }

    private URI target() {
        return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/sec/bscw.cgi/123");
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            assertThat(exchange.getRequestMethod()).isEqualTo("PROPFIND");
            assertThat(exchange.getRequestHeaders().getFirst("Depth")).isNotBlank();
            byte[] body = "<d:multistatus xmlns:d=\"DAV:\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(207, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}

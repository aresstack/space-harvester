package com.aresstack.spaceharvester.source.confluence;

import static com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransportTest.failure;
import static com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransportTest.value;
import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_ALIAS;
import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.source.network.PacServer;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TestProxy;
import com.aresstack.spaceharvester.source.tls.HttpsFakeConfluenceServer;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parität des asynchronen Transports mit #33/#34: echtes PAC (GraalJS) wählt je Ziel-URL die Route, HTTPS geht
 * über den Proxy per {@code CONNECT}, ein PAC-Fehler wird nie still DIRECT, und das Client-Zertifikat aus
 * {@link ConfiguredClientCertificates} (PKCS12) erreicht eine Confluence mit Pflicht-Clientzertifikat direkt und
 * durch den Tunnel. Testnamen {@code *.test} zeigen über {@code jdk.net.hosts.file} auf Loopback, damit eine
 * Direktverbindung gelingen würde.
 */
class WebClientPacMutualTlsTest {

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static HttpsFakeConfluenceServer mtlsServer;
    private static FakeConfluenceServer plainServer;
    private static TestProxy proxy;
    private static PacServer pac;
    private static SSLContext clientTls;
    private static SSLContext trustOnly;

    private final List<WebClientConfluenceTransport> transports = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        certificates = new TestCertificates(dir);
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        mtlsServer = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), true);
        plainServer = new FakeConfluenceServer(confluence);
        proxy = new TestProxy();
        pac = new PacServer("""
                function FindProxyForURL(url, host) {
                  if (shExpMatch(host, "*.proxied.test")) return "PROXY 127.0.0.1:%d";
                  return "DIRECT";
                }
                """.formatted(proxy.port()));
        X509TrustManager trust = certificates.harvesterTrustManager();
        ConfiguredClientCertificates components = new ConfiguredClientCertificates(
                ref -> new SecretMaterial(ref, null, CLIENT_PASSWORD.toCharArray()), trust, null);
        clientTls = components.sslContext(CLIENT_ALIAS, certificates.client, SecretRef.of("test:p12"));
        trustOnly = SSLContext.getInstance("TLS");
        trustOnly.init(null, new TrustManager[] {trust}, null);
    }

    @AfterAll
    static void stop() throws Exception {
        mtlsServer.close();
        plainServer.close();
        pac.close();
        proxy.close();
    }

    @BeforeEach
    void forgetRequests() {
        proxy.requestLines().clear();
        proxy.headerBlocks().clear();
    }

    @AfterEach
    void closeTransports() {
        transports.forEach(WebClientConfluenceTransport::close);
    }

    private WebClientConfluenceTransport transport(URI pacUrl) {
        ProxySettings settings = new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, pacUrl, null, null, null,
                Duration.ofSeconds(5), null, null);
        WebClientConfluenceTransport transport = WebClientConfluenceTransport.builder()
                .routes(ProxyRoutes.from(settings))
                .clientTls(clientTls)
                .anonymousTls(trustOnly)
                .readTimeout(Duration.ofSeconds(5))
                .build();
        transports.add(transport);
        return transport;
    }

    private static URI on(URI base, String host, String path) {
        return URI.create(base.toString().replace("127.0.0.1", host) + path);
    }

    private static Map<String, String> pat() {
        return Map.of("Authorization", "Bearer pat-token", "Accept", "application/json");
    }

    @Test
    void twoTargetsTakeTheRouteTheirPacEntryNames() throws Exception {
        WebClientConfluenceTransport transport = transport(pac.url());
        URI base = URI.create(plainServer.baseUrl().toString());

        ConfluenceHttpResponse direct = value(transport.getAnonymousAsync(on(base, "confluence.direct.test",
                "/rest/api/space"), Map.of(), 1 << 20));
        assertThat(direct.status()).isEqualTo(200);
        assertThat(proxy.requestLines()).isEmpty();

        ConfluenceHttpResponse proxied = value(transport.getAnonymousAsync(on(base, "confluence.proxied.test",
                "/rest/api/space"), Map.of(), 1 << 20));
        assertThat(proxied.status()).isEqualTo(200);
        assertThat(proxied.body()).isEqualTo(direct.body());
        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("confluence.proxied.test:" + base.getPort()));
    }

    @Test
    void patAndClientCertificateReachConfluenceThroughThePacTunnel() throws Exception {
        URI target = on(mtlsServer.baseUrl(), "secure.proxied.test", "/rest/api/space");

        ConfluenceHttpResponse response = value(transport(pac.url()).getAsync(target, pat(), 1 << 20));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyAsUtf8()).contains("\"DEV\"");
        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:"
                        + mtlsServer.baseUrl().getPort() + " "));
        assertThat(proxy.headerBlocks()).noneSatisfy(head -> assertThat(head).contains("pat-token"));
    }

    @Test
    void delegatedCallsUseTheUserTokenWithTheSameCertificateThroughTheTunnel() throws Exception {
        URI base = on(mtlsServer.baseUrl(), "secure.proxied.test", "");
        ProxySettings settings = new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, pac.url(), null, null, null,
                Duration.ofSeconds(5), null, null);
        try (DelegatedConfluenceHttp delegated = ConfluenceSources.delegatedHttp(base,
                ref -> new SecretMaterial(ref, null, CLIENT_PASSWORD.toCharArray()), ProxyRoutes.from(settings),
                "test", certificates.harvesterTrustManager(), null, CLIENT_ALIAS, certificates.client,
                SecretRef.of("test:p12"))) {

            ConfluenceHttpResponse response = value(delegated.get("/rest/api/space", "nutzer-token"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(proxy.requestLines()).isNotEmpty()
                    .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:"));
            assertThat(proxy.headerBlocks()).noneSatisfy(head -> assertThat(head).contains("nutzer-token"));
        }
    }

    @Test
    void theClientCertificateWorksOnADirectRouteToo() throws Exception {
        URI target = URI.create(mtlsServer.baseUrl() + "/rest/api/space");

        assertThat(value(transport(pac.url()).getAsync(target, pat(), 1 << 20)).status()).isEqualTo(200);
        assertThat(proxy.requestLines()).isEmpty();
    }

    @Test
    void theAnonymousRequestShowsNoClientCertificate() {
        URI target = on(mtlsServer.baseUrl(), "secure.proxied.test", "/rest/api/space");

        assertThat(failure(transport(pac.url()).getAnonymousAsync(target, Map.of(), 1 << 20)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aHostOutsideTheServerCertificateIsRejected() {
        // confluence.direct.test zeigt auf denselben Server, steht aber nicht im Zertifikat (nur 127.0.0.1 und
        // secure.proxied.test): Hostnamenprüfung wie bei HttpsURLConnection.
        URI target = on(mtlsServer.baseUrl(), "confluence.direct.test", "/rest/api/space");

        assertThat(failure(transport(pac.url()).getAsync(target, pat(), 1 << 20))).isInstanceOf(IOException.class);
    }

    @Test
    void anUnreachablePacNeverFallsBackToDirect() throws Exception {
        URI missing;
        try (PacServer gone = new PacServer("function FindProxyForURL(u, h) { return \"DIRECT\"; }")) {
            missing = gone.url();
        }
        URI target = on(URI.create(plainServer.baseUrl().toString()), "confluence.proxied.test", "/rest/api/space");

        assertThat(failure(transport(missing).getAnonymousAsync(target, Map.of(), 1 << 20)))
                .isInstanceOf(IOException.class).hasMessageContaining("nicht verfügbar");
        assertThat(proxy.requestLines()).isEmpty();
    }
}

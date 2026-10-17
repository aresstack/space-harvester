package com.aresstack.spaceharvester.source.network;

import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_ALIAS;
import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.tls.HttpsFakeConfluenceServer;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gemeinsames Gate von #33 und #34: PAT und Client-Zertifikat erreichen eine HTTPS-Confluence, die ein
 * Client-Zertifikat verlangt, über die vom PAC gewählte Proxy-Route per {@code CONNECT}. Der Proxy sieht nur den
 * Tunnel, weder Token noch Klartext.
 */
class ProxiedMutualTlsTest {

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static FakeConfluence confluence;
    private static HttpsFakeConfluenceServer mtlsServer;
    private static TrustBundles bundles;
    private static TestProxy proxy;
    private static PacServer pac;

    @BeforeAll
    static void start() throws Exception {
        certificates = new TestCertificates(dir);
        confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        mtlsServer = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), true);
        X509TrustManager trust = certificates.harvesterTrustManager();
        bundles = name -> trust;
        proxy = new TestProxy();
        pac = new PacServer("""
                function FindProxyForURL(url, host) {
                  if (shExpMatch(host, "*.proxied.test")) return "PROXY 127.0.0.1:%d";
                  return "DIRECT";
                }
                """.formatted(proxy.port()));
    }

    @AfterAll
    static void stop() throws Exception {
        mtlsServer.close();
        pac.close();
        proxy.close();
    }

    @BeforeEach
    void forgetRequests() {
        proxy.requestLines().clear();
        proxy.headerBlocks().clear();
    }

    private static HarvestSource source(ClientCertificate certificate) {
        URI base = URI.create(mtlsServer.baseUrl().toString().replace("127.0.0.1", "secure.proxied.test"));
        ProxySettings settings = new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, pac.url(), null, null, null,
                null, null, null);
        return ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence", null, base,
                new Auth(AuthType.PAT, null, null, "pat-token"), Map.of(), certificate, "confluence-ca")), "test",
                bundles, settings).find("wiki").orElseThrow();
    }

    @Test
    void patAndClientCertificateReachConfluenceThroughThePacProxyTunnel() {
        List<Partition> spaces = source(new ClientCertificate(true, CertificateStoreType.PKCS12, certificates.client,
                CLIENT_ALIAS, CLIENT_PASSWORD, null)).partitions();

        assertThat(spaces).extracting(Partition::key).contains("DEV");
        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:" + mtlsServer.baseUrl()
                        .getPort() + " "));
        assertThat(proxy.headerBlocks()).noneSatisfy(head -> assertThat(head).contains("pat-token"));
        assertThat(confluence.requestHeaders()).last().satisfies(headers -> assertThat(headers)
                .containsEntry("Authorization", "Bearer pat-token"));
    }

    @Test
    void withoutClientCertificateTheTunnelAloneIsNotEnough() {
        HarvestSource source = source(null);

        assertThatThrownBy(source::partitions).isInstanceOf(Exception.class);
        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:"));
    }
}

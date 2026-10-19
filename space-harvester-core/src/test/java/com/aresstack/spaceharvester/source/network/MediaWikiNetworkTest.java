package com.aresstack.spaceharvester.source.network;

import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_ALIAS;
import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.FakeMediaWikiTransport;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.InvalidSourceConfigurationException;
import com.aresstack.spaceharvester.source.MediaWikiSources;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.WikiFront;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
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
 * Netzweg des Quelltyps {@code mediawiki} (#187) wie beim Confluence-Typ: Proxy-Route je Ziel aus dem PAC-Skript, eine
 * nicht bestimmbare Route lässt die Anfrage scheitern statt still direkt zu gehen, Server-Vertrauen aus
 * {@code trust-bundle} und Client-Zertifikat (mTLS), auch durch den Proxy-Tunnel. Die Testnamen {@code *.test} zeigen
 * über {@code jdk.net.hosts.file} auf Loopback: Eine Direktverbindung würde gelingen.
 */
class MediaWikiNetworkTest {

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static TrustBundles bundles;
    private static TestProxy proxy;
    private static PacServer pac;
    private static WikiFront plain;
    private static WikiFront mtls;

    @BeforeAll
    static void start() throws Exception {
        certificates = new TestCertificates(dir);
        X509TrustManager trust = certificates.harvesterTrustManager();
        bundles = name -> {
            if (!name.equals("wiki-ca")) {
                throw new IllegalArgumentException("SSL-Bundle '" + name + "' ist nicht konfiguriert");
            }
            return trust;
        };
        proxy = new TestProxy();
        pac = new PacServer(script());
        plain = new WikiFront(wiki());
        mtls = new WikiFront(wiki(), certificates.serverContext(), true);
    }

    @AfterAll
    static void stop() throws Exception {
        plain.close();
        mtls.close();
        pac.close();
        proxy.close();
    }

    @BeforeEach
    void reset() {
        pac.script(script());
        proxy.requestLines().clear();
        proxy.headerBlocks().clear();
        plain.queries().clear();
        mtls.queries().clear();
    }

    private static String script() {
        return """
                function FindProxyForURL(url, host) {
                  if (shExpMatch(host, "*.proxied.test")) return "PROXY 127.0.0.1:%d";
                  return "DIRECT";
                }
                """.formatted(proxy.port());
    }

    private static FakeMediaWikiTransport wiki() {
        FakeMediaWikiTransport wiki = new FakeMediaWikiTransport();
        wiki.page("Hauptseite", "<p>Willkommen</p>", 1, 11, "Betrieb");
        wiki.page("Betrieb", "<p>Nachtlauf</p>", 2, 12);
        return wiki;
    }

    private static HarvestSource source(String apiUrl, ClientCertificate certificate, String trustBundle) {
        SourceSettingsConfig config = new SourceSettingsConfig("wiki", MediaWikiSources.TYPE, null, null, null,
                Map.of("apiUrl", apiUrl, "startPoints", "Hauptseite"), certificate, trustBundle);
        return ConfiguredSourceCatalog.of(List.of(config), "test", bundles, new ProxySettings(
                ProxySettings.Mode.PAC_URL_MANUAL, pac.url(), null, null, null, null, null, null))
                .find("wiki").orElseThrow();
    }

    private static List<String> titles(HarvestSource source) {
        return source.pageMetadata("wiki", null, 10).pages().stream().map(PageMetadata::title).toList();
    }

    private static ClientCertificate client() {
        return new ClientCertificate(true, CertificateStoreType.PKCS12, certificates.client, CLIENT_ALIAS,
                CLIENT_PASSWORD, null);
    }

    @Test
    void requestsGoThroughTheProxyThePacScriptChoosesForTheWiki() {
        assertThat(titles(source(plain.apiUrl("wiki.proxied.test"), null, null)))
                .containsExactly("Hauptseite", "Betrieb");

        assertThat(proxy.requestLines()).isNotEmpty().allSatisfy(line ->
                assertThat(line).startsWith("GET http://wiki.proxied.test:"));
        assertThat(plain.queries()).isNotEmpty();
    }

    @Test
    void directPacRouteBypassesTheProxy() {
        assertThat(titles(source(plain.apiUrl("wiki.direct.test"), null, null))).hasSize(2);

        assertThat(proxy.requestLines()).isEmpty();
    }

    @Test
    void anUndeterminableRouteFailsInsteadOfConnectingDirectly() {
        pac.script(null);
        HarvestSource source = source(plain.apiUrl("wiki.direct.test"), null, null);

        assertThatThrownBy(() -> titles(source)).isInstanceOf(SourceAccessException.class)
                .satisfies(e -> assertThat(((SourceAccessException) e).reason())
                        .isEqualTo(SourceAccessException.Reason.UNAVAILABLE));

        assertThat(pac.downloads()).isPositive();
        assertThat(plain.queries()).isEmpty();
        assertThat(proxy.requestLines()).isEmpty();
    }

    @Test
    void clientCertificateReachesTheWikiThroughThePacProxyTunnel() {
        assertThat(titles(source(mtls.apiUrl("secure.proxied.test"), client(), "wiki-ca"))).hasSize(2);

        assertThat(proxy.requestLines()).isNotEmpty().allSatisfy(line ->
                assertThat(line).startsWith("CONNECT secure.proxied.test:"));
        assertThat(mtls.queries()).isNotEmpty();
    }

    @Test
    void withoutClientCertificateTheWikiRefusesTheConnection() {
        HarvestSource source = source(mtls.apiUrl(), null, "wiki-ca");

        assertThatThrownBy(() -> titles(source)).isInstanceOf(SourceAccessException.class);
        assertThat(mtls.queries()).isEmpty();
    }

    @Test
    void withoutTheWikisCaTheServerIsNotTrusted() {
        HarvestSource source = source(mtls.apiUrl(), client(), null);

        assertThatThrownBy(() -> titles(source)).isInstanceOf(SourceAccessException.class);
        assertThat(mtls.queries()).isEmpty();
    }

    @Test
    void unusableCertificatesPreventTheStartWithoutRevealingThePassword() {
        assertThatThrownBy(() -> source(mtls.apiUrl(), new ClientCertificate(true, CertificateStoreType.PKCS12,
                certificates.client, CLIENT_ALIAS, "falsch-0815", null), "wiki-ca"))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("client-certificate:").hasMessageNotContaining("falsch-0815");
        assertThatThrownBy(() -> source(mtls.apiUrl(), new ClientCertificate(true, CertificateStoreType.PKCS12,
                certificates.certificateOnly, CLIENT_ALIAS, CLIENT_PASSWORD, null), "wiki-ca"))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("keinen privaten Schlüssel");
        assertThatThrownBy(() -> source(mtls.apiUrl(), client(), "unbekannt"))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("trust-bundle: SSL-Bundle 'unbekannt' ist nicht konfiguriert");
    }
}

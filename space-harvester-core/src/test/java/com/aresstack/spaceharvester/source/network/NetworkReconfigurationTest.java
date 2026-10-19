package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.tls.HttpsFakeConfluenceServer;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Netzwerkeinstellungen ohne Neustart (ADR 0015): {@link ConfiguredSourceCatalog#reconfigure} stellt Proxy und
 * Vertrauen ohne Bundle um und öffnet die Quellen neu, sodass auch Trust-Bundle-Dateien neu gelesen werden.
 */
class NetworkReconfigurationTest {

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static HttpsFakeConfluenceServer server;
    private static TestProxy proxy;

    @BeforeAll
    static void start() throws Exception {
        certificates = new TestCertificates(dir);
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        server = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), false);
        proxy = new TestProxy();
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        proxy.close();
    }

    @BeforeEach
    void forgetRequests() {
        proxy.requestLines().clear();
        proxy.headerBlocks().clear();
    }

    private static SourceSettingsConfig wiki(URI base, String trustBundle) {
        return new SourceSettingsConfig("wiki", "confluence", null, base, null, Map.of(), null, trustBundle);
    }

    private static ProxySettings manual() {
        return new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "127.0.0.1", proxy.port(), null, null, null,
                null);
    }

    private static List<String> spaces(ConfiguredSourceCatalog catalog) {
        return catalog.find("wiki").orElseThrow().partitions().stream().map(Partition::key).toList();
    }

    @Test
    void theProxyIsSwitchedOnAndOffWithoutRestart() throws Exception {
        // Ein Name statt 127.0.0.1: Loopback-Ziele gehen immer direkt (test-hosts zeigt ihn auf Loopback).
        URI base = URI.create(server.baseUrl().toString().replace("127.0.0.1", "secure.proxied.test"));
        X509TrustManager trust = certificates.harvesterTrustManager();
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(wiki(base, "confluence-ca")),
                "test", TrustBundles.reconfigurable(name -> trust, TrustSettings.jvm()), ProxySettings.disabled())) {
            assertThat(spaces(catalog)).containsExactly("DEV");
            assertThat(proxy.requestLines()).isEmpty();

            catalog.reconfigure(new NetworkSettings(manual(), TrustSettings.jvm()));

            assertThat(catalog.route("wiki")).hasValueSatisfying(route -> assertThat(route).contains("PROXY"));
            assertThat(spaces(catalog)).containsExactly("DEV");
            assertThat(proxy.requestLines()).isNotEmpty()
                    .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:"));

            proxy.requestLines().clear();
            catalog.reconfigure(new NetworkSettings(ProxySettings.disabled(), TrustSettings.jvm()));

            assertThat(catalog.route("wiki")).hasValueSatisfying(route -> assertThat(route).contains("DIRECT"));
            assertThat(spaces(catalog)).containsExactly("DEV");
            assertThat(proxy.requestLines()).isEmpty();
        }
    }

    @Test
    void invalidNetworkSettingsAreRejectedAndTheCurrentRouteStays() {
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(wiki(server.baseUrl(), null)),
                "test", TrustBundles.reconfigurable(TrustBundles.NONE, TrustSettings.jvm()), manual())) {
            assertThatThrownBy(() -> catalog.reconfigure(new NetworkSettings(new ProxySettings(
                    ProxySettings.Mode.MANUAL_PROXY, null, null, null, null, null, null, null), TrustSettings.jvm())))
                    .isInstanceOf(InvalidNetworkConfigurationException.class)
                    .hasMessageContaining("proxy-host");

            assertThat(catalog.routes().describe()).startsWith("manual-proxy 127.0.0.1:" + proxy.port());
        }
    }

    @Test
    void aServerWithItsOwnCertificateIsReachableOnceTheCaFileIsActivated() {
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(wiki(server.baseUrl(), null)),
                "test", TrustBundles.reconfigurable(TrustBundles.NONE, TrustSettings.jvm()),
                ProxySettings.disabled())) {
            assertThatThrownBy(() -> spaces(catalog)).isInstanceOf(RuntimeException.class);

            catalog.reconfigure(new NetworkSettings(ProxySettings.disabled(),
                    new TrustSettings(true, false, false, dir.resolve("server.crt"))));

            assertThat(spaces(catalog)).containsExactly("DEV");
        }
    }

    @Test
    void trustBundleFilesAreReadAgainAfterActivation() throws Exception {
        Path bundle = dir.resolve("bundle.p12");
        // Zuerst ein Truststore, der den Server nicht kennt; die Bundles lesen die Datei bei jedem Öffnen.
        Files.copy(certificates.serverTrust, bundle, StandardCopyOption.REPLACE_EXISTING);
        TrustBundles bundles = name -> load(bundle);
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(wiki(server.baseUrl(), "file")),
                "test", TrustBundles.reconfigurable(bundles, TrustSettings.jvm()), ProxySettings.disabled())) {
            assertThatThrownBy(() -> spaces(catalog)).isInstanceOf(RuntimeException.class);

            Files.copy(certificates.harvesterTrust, bundle, StandardCopyOption.REPLACE_EXISTING);
            assertThatThrownBy(() -> spaces(catalog)).isInstanceOf(RuntimeException.class);
            catalog.reconfigure(NetworkSettings.DEFAULT);

            assertThat(spaces(catalog)).containsExactly("DEV");
        }
    }

    @Test
    void sourcesWithTheSameTargetAreOpenedAgainToo() throws Exception {
        // Beim Start nur eine Warnung; auch dann liest die Umstellung ihr Vertrauen neu.
        Path bundle = dir.resolve("bundle-twice.p12");
        Files.copy(certificates.serverTrust, bundle, StandardCopyOption.REPLACE_EXISTING);
        TrustBundles bundles = name -> load(bundle);
        SourceSettingsConfig twin = new SourceSettingsConfig("wiki2", "confluence", null, server.baseUrl(), null,
                Map.of(), null, "file");
        try (ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(wiki(server.baseUrl(), "file"),
                twin), "test", TrustBundles.reconfigurable(bundles, TrustSettings.jvm()), ProxySettings.disabled())) {
            Files.copy(certificates.harvesterTrust, bundle, StandardCopyOption.REPLACE_EXISTING);
            catalog.reconfigure(NetworkSettings.DEFAULT);

            assertThat(spaces(catalog)).containsExactly("DEV");
            assertThat(catalog.find("wiki2").orElseThrow().partitions()).extracting(Partition::key)
                    .containsExactly("DEV");
        }
    }

    private static X509TrustManager load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, TestCertificates.STORE_PASSWORD.toCharArray());
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return (X509TrustManager) factory.getTrustManagers()[0];
        } catch (Exception e) {
            throw new IllegalArgumentException("Truststore nicht lesbar", e);
        }
    }
}

package com.aresstack.spaceharvester.source.network;

import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_ALIAS;
import static com.aresstack.spaceharvester.source.tls.TestCertificates.CLIENT_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog.AsyncTransport;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Native Seiten-Snapshots asynchron (#47) über die ganze Kette: Konfiguration {@code web-client}, PAC wählt den
 * Proxy, HTTPS per {@code CONNECT}, PAT und Client-Zertifikat (PKCS12) aus derselben Quellenkonfiguration wie der
 * synchrone Weg, Antwort byte-gleich mit {@code storedPage}.
 */
class AsyncSnapshotsTest {

    @TempDir
    static Path dir;

    private static TestCertificates certificates;
    private static FakeConfluence confluence;
    private static HttpsFakeConfluenceServer mtlsServer;
    private static TrustBundles bundles;
    private static TestProxy proxy;
    private static PacServer pac;

    private ConfiguredSourceCatalog catalog;

    @BeforeAll
    static void start() throws Exception {
        certificates = new TestCertificates(dir);
        confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        confluence.page("100", "Start", "DEV", "<p>Start</p>");
        confluence.page("101", "Anleitung", "DEV", "<p>Schritt 1</p>").storage("<p>Schritt 1</p>");
        confluence.child("100", "101");
        confluence.homepage("DEV", "100");
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

    @AfterEach
    void closeCatalog() {
        if (catalog != null) {
            catalog.close();
        }
    }

    private HarvestSource source(AsyncTransport mode) {
        URI base = URI.create(mtlsServer.baseUrl().toString().replace("127.0.0.1", "secure.proxied.test"));
        ProxySettings settings = new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, pac.url(), null, null, null,
                null, null, null);
        catalog = ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence", null, base,
                new Auth(AuthType.PAT, null, null, "pat-token"), Map.of(),
                new ClientCertificate(true, CertificateStoreType.PKCS12, certificates.client, CLIENT_ALIAS,
                        CLIENT_PASSWORD, null), "confluence-ca")), "test", bundles, settings, mode);
        return catalog.find("wiki").orElseThrow();
    }

    @Test
    void theSnapshotArrivesAsynchronouslyThroughThePacTunnelWithTheClientCertificate() throws Exception {
        HarvestSource source = source(AsyncTransport.WEB_CLIENT);
        AtomicReference<Thread> completedOn = new AtomicReference<>();

        CompletableFuture<StoredPage> stage = source.storedPageAsync("101")
                .whenComplete((page, error) -> completedOn.set(Thread.currentThread())).toCompletableFuture();
        StoredPage async = stage.get(10, TimeUnit.SECONDS);

        assertThat(source.asyncStoredPages()).isTrue();
        assertThat(completedOn.get()).isNotSameAs(Thread.currentThread());
        assertThat(completedOn.get().getName()).startsWith("confluence-snapshot-");
        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:"));
        assertThat(proxy.headerBlocks()).noneSatisfy(head -> assertThat(head).contains("pat-token"));
        assertThat(confluence.requestHeaders()).last().satisfies(headers -> assertThat(headers)
                .containsEntry("Authorization", "Bearer pat-token"));

        StoredPage sync = source.storedPage("101");
        assertThat(async.payload()).isEqualTo(sync.payload());
        assertThat(async.metadata()).isEqualTo(sync.metadata());
        assertThat(async.nativeFormat()).isEqualTo(sync.nativeFormat());
    }

    @Test
    void anUnknownPageFailsTheStageLikeTheBlockingCall() {
        HarvestSource source = source(AsyncTransport.WEB_CLIENT);

        assertThat(source.storedPageAsync("999").toCompletableFuture())
                .failsWithin(10, TimeUnit.SECONDS).withThrowableOfType(ExecutionException.class)
                .havingCause().isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
    }

    @Test
    void withoutTheSettingSnapshotsStaySynchronous() {
        HarvestSource source = source(AsyncTransport.DISABLED);

        assertThat(source.asyncStoredPages()).isFalse();
        assertThat(source.storedPageAsync("101").toCompletableFuture())
                .failsWithin(1, TimeUnit.SECONDS).withThrowableOfType(ExecutionException.class)
                .havingCause().isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNSUPPORTED));
        assertThat(source.storedPage("101").payload()).isNotEmpty();
    }
}

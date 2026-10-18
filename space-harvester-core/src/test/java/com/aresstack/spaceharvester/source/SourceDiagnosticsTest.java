package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.application.sources.ConnectionStep.Stage;
import com.aresstack.spaceharvester.application.sources.ConnectionStep.Status;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog.AsyncTransport;
import com.aresstack.spaceharvester.source.network.ConnectionDiagnosis;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TestProxy;
import com.aresstack.spaceharvester.source.tls.HttpsFakeConfluenceServer;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verbindungsdiagnose in Stufen (#189): jede Stufe meldet Ergebnis, Ursache und Hinweis. */
class SourceDiagnosticsTest {

    private static final String PASSWORD = "geheim-0815";

    @TempDir
    static Path dir;

    private static HttpsFakeConfluenceServer server;
    private static TrustBundles bundles;
    private ConfiguredSourceCatalog catalog;
    private TestProxy proxy;

    @BeforeAll
    static void start() throws Exception {
        TestCertificates certificates = new TestCertificates(dir);
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        server = new HttpsFakeConfluenceServer(confluence, certificates.serverContext(), false);
        X509TrustManager trust = certificates.harvesterTrustManager();
        bundles = name -> trust;
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.close();
    }

    @AfterEach
    void stop() throws Exception {
        if (catalog != null) {
            catalog.close();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    private static SourceSettingsConfig config(URI base, String trustBundle) {
        return new SourceSettingsConfig("wiki", "confluence", null, base, null, Map.of(), null, trustBundle);
    }

    private SourceDiagnostics diagnostics(ProxySettings proxySettings) {
        catalog = ConfiguredSourceCatalog.of(List.of(), "test", bundles, proxySettings, AsyncTransport.DISABLED,
                ConfiguredSourceCatalog.DEFAULT_ADAPTERS, null);
        return new SourceDiagnostics(catalog, Duration.ofSeconds(5));
    }

    private static List<Status> statuses(SourceDiagnosis diagnosis) {
        return diagnosis.steps().stream().map(ConnectionStep::status).toList();
    }

    @Test
    void aReachableSourcePassesEveryStage() {
        SourceDiagnosis diagnosis = diagnostics(ProxySettings.disabled())
                .diagnose(config(server.baseUrl(), "ca"), false);

        assertThat(diagnosis.successful()).isTrue();
        assertThat(diagnosis.steps()).extracting(ConnectionStep::stage).containsExactly(Stage.values());
        assertThat(statuses(diagnosis)).containsExactly(Status.OK, Status.OK, Status.OK, Status.OK, Status.INFO,
                Status.OK);
        assertThat(diagnosis.steps().get(3).message()).contains("Handshake mit 127.0.0.1")
                .contains("Serverzertifikat für 127.0.0.1");
        assertThat(diagnosis.steps().get(5).message()).isEqualTo("1 Space sichtbar");
    }

    @Test
    void anUntrustedCertificateStopsAtTlsWithAHint() {
        SourceDiagnosis diagnosis = diagnostics(ProxySettings.disabled())
                .diagnose(config(server.baseUrl(), null), false);

        assertThat(statuses(diagnosis)).containsExactly(Status.OK, Status.OK, Status.OK, Status.FAILED,
                Status.SKIPPED, Status.SKIPPED);
        assertThat(diagnosis.failure().orElseThrow().hint()).contains("tls.ca-certificates-file")
                .contains("trust-bundle");
    }

    @Test
    void anUnknownHostStopsAtDns() {
        SourceDiagnosis diagnosis = diagnostics(ProxySettings.disabled())
                .diagnose(config(URI.create("https://confluence.invalid/"), null), false);

        assertThat(diagnosis.failure().orElseThrow().stage()).isEqualTo(Stage.DNS);
        assertThat(diagnosis.failure().orElseThrow().hint()).contains("Hostname lässt sich nicht auflösen");
        assertThat(statuses(diagnosis)).endsWith(Status.SKIPPED, Status.SKIPPED, Status.SKIPPED, Status.SKIPPED);
    }

    @Test
    void aProxyDemandingALoginIsNamedAsTheCause() throws Exception {
        proxy = new TestProxy().requireBasic("proxy-nutzer", PASSWORD);
        SourceDiagnosis diagnosis = diagnostics(proxy(null)).diagnose(config(proxied(), "ca"), false);

        ConnectionStep failure = diagnosis.failure().orElseThrow();
        assertThat(failure.stage()).isEqualTo(Stage.TCP);
        assertThat(failure.message()).contains("HTTP 407").contains("Basic");
        assertThat(failure.hint()).contains("space-harvester.network.proxy.auth");
    }

    @Test
    void withTheProxyLoginTheTunnelAndAllStagesPass() throws Exception {
        proxy = new TestProxy().requireBasic("proxy-nutzer", PASSWORD);
        SourceDiagnosis diagnosis = diagnostics(proxy(new ProxySettings.Authentication("proxy-nutzer",
                PASSWORD.toCharArray()))).diagnose(config(proxied(), "ca"), false);

        assertThat(diagnosis.successful()).as(diagnosis.summary()).isTrue();
        assertThat(diagnosis.steps().get(1).message()).contains("das Ziel löst der Proxy auf");
        assertThat(diagnosis.steps().get(2).message()).contains("Tunnel über Proxy")
                .contains("Anmeldung am Proxy: BASIC");
        assertThat(diagnosis.steps()).allSatisfy(step -> assertThat(step.message() + step.hint())
                .doesNotContain(PASSWORD));
    }

    @Test
    void invalidSettingsAreRejectedBeforeAnyConnection() {
        SourceDiagnostics diagnostics = diagnostics(ProxySettings.disabled());

        assertThatThrownBy(() -> diagnostics.diagnose(config(null, null), false))
                .isInstanceOf(InvalidSourceConfigurationException.class);
        assertThat(diagnostics.diagnose("fehlt")).isEmpty();
    }

    @Test
    void detailsMaskCredentials() {
        assertThat(ConnectionDiagnosis.detail(new IllegalStateException("Header Authorization: Bearer abc.def",
                new RuntimeException("Basic dXNlcjpwdw=="))))
                .contains("Bearer ***").contains("Basic ***").doesNotContain("abc.def").doesNotContain("dXNlcjpwdw");
    }

    private ProxySettings proxy(ProxySettings.Authentication authentication) {
        return new ProxySettings(ProxySettings.Mode.MANUAL_PROXY, null, "127.0.0.1", proxy.port(), null, null, null,
                null, authentication);
    }

    private static URI proxied() {
        return URI.create(server.baseUrl().toString().replace("127.0.0.1", "secure.proxied.test"));
    }
}

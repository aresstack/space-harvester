package com.aresstack.spaceharvester.source.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * PAC-Auswertung durch {@code win-proxy-java} mit GraalJS unter Java 21 und die tatsächliche Anwendung der Route im
 * Confluence-Transport. Die Testnamen {@code *.test} zeigen über {@code jdk.net.hosts.file} auf Loopback (siehe
 * {@code src/test/resources/test-hosts}): Eine Direktverbindung würde also gelingen, ein Fehlschlag beweist
 * Fail-Closed.
 */
@ExtendWith(OutputCaptureExtension.class)
class PacRoutingTest {

    private static final String SENTINEL = "PROXY 192.0.2.123:18080";

    private PacServer pac;
    private TestProxy proxy;
    private FakeConfluenceServer confluence;

    @BeforeEach
    void start() throws Exception {
        proxy = new TestProxy();
        pac = new PacServer("""
                function FindProxyForURL(url, host) {
                  if (shExpMatch(host, "*.proxied.test")) return "PROXY 127.0.0.1:%d";
                  if (host == "confluence.sentinel.test") return "%s";
                  if (shExpMatch(url, "http://*/only-this-path/*")) return "%s; DIRECT";
                  return "DIRECT";
                }
                """.formatted(proxy.port(), SENTINEL, SENTINEL));
        FakeConfluence fake = new FakeConfluence();
        fake.space("DEV", "Entwicklung");
        confluence = new FakeConfluenceServer(fake);
    }

    @AfterEach
    void stop() throws Exception {
        confluence.close();
        pac.close();
        proxy.close();
    }

    private ProxySettings pacSettings() {
        return new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL, pac.url(), null, null, null, null, null, null);
    }

    private URI base(String host) {
        return URI.create(confluence.baseUrl().toString().replace("127.0.0.1", host));
    }

    /** Anonym, denn mit Zugangsdaten erlaubt der Confluence-Adapter HTTP nur zu Loopback-Adressen. */
    private HarvestSource source(String host, ProxySettings settings) {
        return ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("confluence", "confluence", null,
                base(host), null, Map.of())), "test", settings)
                .find("confluence").orElseThrow();
    }

    @Test
    void pacChoosesDifferentRoutesPerTargetUrl() {
        ProxyRoutes routes = ProxyRoutes.from(pacSettings());

        assertThat(routes.routeFor(URI.create("https://confluence.proxied.test/rest/api/space")))
                .isEqualTo(HttpRoute.proxy("127.0.0.1", proxy.port(), "resolved"));
        assertThat(routes.routeFor(URI.create("https://confluence.sentinel.test/")))
                .isEqualTo(HttpRoute.proxy("192.0.2.123", 18080, "resolved"));
        assertThat(routes.routeFor(URI.create("https://other.example/")).isDirect()).isTrue();
        // HTTP: das PAC-Skript sieht den Pfad.
        assertThat(routes.routeFor(URI.create("http://other.example/only-this-path/x")))
                .isEqualTo(HttpRoute.proxy("192.0.2.123", 18080, "resolved"));
        assertThat(routes.routeFor(URI.create("http://other.example/elsewhere")).isDirect()).isTrue();
    }

    @Test
    void confluenceRequestsReallyGoThroughThePacProxy() throws Exception {
        HarvestSource source = source("confluence.proxied.test", pacSettings());

        assertThat(source.partitions()).extracting(space -> space.key()).contains("DEV");

        assertThat(proxy.requestLines()).isNotEmpty().allSatisfy(line ->
                assertThat(line).startsWith("GET http://confluence.proxied.test:"));
        assertThat(confluence.confluence().requests()).isNotEmpty();
    }

    @Test
    void directPacRouteBypassesTheProxy() throws Exception {
        HarvestSource source = source("confluence.direct.test", pacSettings());

        assertThat(source.partitions()).extracting(space -> space.key()).contains("DEV");
        assertThat(proxy.requestLines()).isEmpty();
    }

    @Test
    void httpsUsesConnectTunnelWithoutSendingCredentialsToTheProxy() {
        HarvestSource source = ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("confluence",
                "confluence", null, URI.create("https://secure.proxied.test/wiki"),
                new Auth(AuthType.PAT, null, null, "geheimes-token"), Map.of())), "test", pacSettings())
                .find("confluence").orElseThrow();

        assertThatThrownBy(source::partitions).isInstanceOf(Exception.class);

        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("CONNECT secure.proxied.test:443 "));
        assertThat(proxy.headerBlocks()).noneSatisfy(head -> assertThat(head).contains("geheimes-token"));
    }

    @Test
    void missingPacFileFailsClosedInsteadOfConnectingDirectly() {
        pac.script(null);
        HarvestSource source = source("confluence.direct.test", pacSettings());

        assertThatThrownBy(source::partitions).hasStackTraceContaining("unavailable");

        assertThat(pac.downloads()).isPositive();
        assertThat(confluence.confluence().requests()).isEmpty();
        assertThat(proxy.requestLines()).isEmpty();
    }

    @Test
    void invalidPacScriptFailsClosed() {
        pac.script("function FindProxyForURL(url, host) { return ; ");
        ProxyRoutes routes = ProxyRoutes.from(pacSettings());

        HttpRoute route = routes.routeFor(URI.create("https://confluence.direct.test/"));

        assertThat(route.isUnavailable()).isTrue();
        assertThatThrownBy(source("confluence.direct.test", pacSettings())::partitions)
                .hasStackTraceContaining("unavailable");
        assertThat(confluence.confluence().requests()).isEmpty();
    }

    @Test
    void manualProxyIsUsedWithoutPac() throws Exception {
        HarvestSource source = source("confluence.direct.test", new ProxySettings(ProxySettings.Mode.MANUAL_PROXY,
                null, "127.0.0.1", proxy.port(), null, null, null, null));

        assertThat(source.partitions()).extracting(space -> space.key()).contains("DEV");
        assertThat(proxy.requestLines()).isNotEmpty()
                .allSatisfy(line -> assertThat(line).startsWith("GET http://confluence.direct.test:"));
    }

    @Test
    void diagnosisShowsEveryLibraryStep() {
        List<String> lines = ProxyRoutes.from(pacSettings()).diagnose(URI.create("https://confluence.sentinel.test/x"));

        assertThat(lines.get(0)).isEqualTo("Resolving https://confluence.sentinel.test/ ...");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("PAC script downloaded"));
        assertThat(lines.get(lines.size() - 1)).startsWith("Result: PROXY 192.0.2.123:18080");
    }

    @Test
    void secretsInTheTargetQueryReachThePacScriptButNotLogsDiagnosisOrErrors(CapturedOutput output) {
        String secret = "GEHEIM-0815-token";
        URI target = URI.create("http://user:pw-" + secret + "@other.example/only-this-path/x?api_key=" + secret);
        ProxyRoutes routes = ProxyRoutes.from(pacSettings());

        // Das PAC-Skript entscheidet weiter anhand der vollständigen URL.
        assertThat(routes.routeFor(target)).isEqualTo(HttpRoute.proxy("192.0.2.123", 18080, "resolved"));
        List<String> lines = routes.diagnose(target);
        assertThat(lines.get(0)).isEqualTo("Resolving http://other.example/only-this-path/x?… ...");

        // Fehler der Bibliothek nennen die Ziel-URL; Route, Diagnose und Log zeigen sie ohne Query.
        pac.script("function FindProxyForURL(url, host) { throw new Error('kaputt ' + url); }");
        ProxyRoutes broken = ProxyRoutes.from(pacSettings());
        HttpRoute failed = broken.routeFor(target);
        List<String> failedLines = broken.diagnose(target);

        assertThat(failed.isUnavailable()).isTrue();
        assertThat(failed.describe()).contains("other.example/only-this-path/x?…").doesNotContain(secret);
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(secret));
        assertThat(failedLines).noneSatisfy(line -> assertThat(line).contains(secret));
        assertThat(failedLines).anySatisfy(line -> assertThat(line).contains("kaputt"));
        assertThat(output.getAll()).contains("Proxy-Route für http://other.example/only-this-path/x?…")
                .doesNotContain(secret);
    }

    @Test
    void secretsInThePacUrlQueryStayOutOfDiagnosis() {
        String secret = "PAC-GEHEIM-4711";
        ProxySettings settings = new ProxySettings(ProxySettings.Mode.PAC_URL_MANUAL,
                URI.create(pac.url() + "?ticket=" + secret), null, null, null, null, null, null);
        ProxyRoutes routes = ProxyRoutes.from(settings);

        assertThat(routes.describe()).doesNotContain(secret);
        assertThat(routes.diagnose(URI.create("https://confluence.sentinel.test/")))
                .noneSatisfy(line -> assertThat(line).contains(secret))
                .anySatisfy(line -> assertThat(line).startsWith("Result: PROXY 192.0.2.123:18080"));
    }
}

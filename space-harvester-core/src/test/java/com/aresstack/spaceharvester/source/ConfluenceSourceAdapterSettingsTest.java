package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.source.api.AsyncStoredContentSource;
import com.aresstack.spaceharvester.application.jobs.RetrySettings;
import com.aresstack.spaceharvester.source.confluence.AsyncSnapshotTransports;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfluenceSourceAdapterSettingsTest {

    private static final URI BASE = URI.create("https://confluence.example.org/wiki");

    @Test
    void foreignFileRedirectsAreAlwaysOff() {
        assertThat(ConfluenceSourceAdapter.settings(source(Map.of())).get("foreignFileRedirects")).isEqualTo("false");
        assertThat(ConfluenceSourceAdapter.settings(source(Map.of("foreignFileRedirects", "true")))
                .get("foreignFileRedirects")).isEqualTo("false");
    }

    @Test
    void enablingForeignFileRedirectsIsAConfigurationError() {
        ConfluenceSourceAdapter adapter = new ConfluenceSourceAdapter();

        assertThat(adapter.check(source(Map.of("foreignFileRedirects", "true"))))
                .contains("options.foreignFileRedirects ist im Dienst nicht einschaltbar; Weiterleitungen auf fremde"
                        + " Hosts werden nie verfolgt");
        assertThat(adapter.check(source(Map.of("foreignFileRedirects", "false"))))
                .noneMatch(problem -> problem.contains("foreignFileRedirects"));
    }

    private static SourceSettingsConfig source(Map<String, String> options) {
        return new SourceSettingsConfig("wiki", "confluence", null, BASE, null, options);
    }

    @Test
    void theDesktopAppUsesTheDefaultsOfTheEnterpriseAiClient() {
        // #571: wie der EAC keine Wiederholung und Weiterleitungen nach Upstream-Standard.
        var settings = ConfluenceSourceAdapter.settings(source(Map.of()), true);
        assertThat(settings.get("maxRetries")).isEmpty();
        assertThat(settings.get("foreignFileRedirects")).isEmpty();
        assertThat(ConfluenceSourceAdapter.settings(source(Map.of("maxRetries", "1")), true).get("maxRetries"))
                .isEqualTo("1");
        assertThat(ConfluenceSourceAdapter.desktop().check(source(Map.of("foreignFileRedirects", "true"))))
                .noneMatch(problem -> problem.contains("foreignFileRedirects"));
        // Der Dienst bleibt bei seinen Standards.
        assertThat(ConfluenceSourceAdapter.settings(source(Map.of())).get("maxRetries")).isEqualTo("2");
    }

    @Test
    void jobsRetryBecomesTheRetryOfTheRestClientUnlessTheSourceSetsItsOwn() {
        // ADR 0025: eine Schleife, die des REST-Clients; Desktop und Dienst lesen dieselben Werte.
        RetrySettings retry = new RetrySettings(4, Duration.ofMillis(1500), Duration.ofSeconds(20), 5);
        for (boolean desktop : new boolean[] {true, false}) {
            var settings = ConfluenceSourceAdapter.settings(source(Map.of()), desktop, retry);
            assertThat(settings.get("maxRetries")).isEqualTo("4");
            assertThat(settings.get("retryBackoffMillis")).isEqualTo("1500");
            assertThat(settings.get("maxRetryBackoffMillis")).isEqualTo("20000");
        }
        var own = ConfluenceSourceAdapter.settings(source(Map.of("maxRetries", "0", "retryBackoffMillis", "100")),
                true, retry);
        assertThat(own.get("maxRetries")).isEqualTo("0");
        assertThat(own.get("retryBackoffMillis")).isEqualTo("100");
        assertThat(own.get("maxRetryBackoffMillis")).isEqualTo("20000");
    }

    @Test
    void theDesktopAppBuildsConfluenceLikeTheEnterpriseAiClientWithoutAsyncTransport() {
        // #571: EAC 0.80.98 (AdapterAssembly.sourceProviders) baut den Adapter ohne asynchronen Transport; alle
        // Abrufe einer Quelle laufen über denselben UrlConnection-Transport. Der Dienst behält WebClient-Snapshots.
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        ProxyRoutes routes = ProxyRoutes.from(ProxySettings.disabled());
        try (AsyncSnapshotTransports async = new AsyncSnapshotTransports(secrets, routes, "test")) {
            SourceAdapter.Context context = new SourceAdapter.Context(secrets, routes, "test", TrustBundles.NONE,
                    async, null);

            assertThat(new ConfluenceSourceAdapter().open(source(Map.of()), context).port())
                    .isInstanceOf(AsyncStoredContentSource.class);
            assertThat(ConfluenceSourceAdapter.desktop(null).open(source(Map.of()), context).port())
                    .isNotInstanceOf(AsyncStoredContentSource.class);
        }
    }
}

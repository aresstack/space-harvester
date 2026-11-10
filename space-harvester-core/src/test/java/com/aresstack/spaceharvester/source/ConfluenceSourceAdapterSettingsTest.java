package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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
}

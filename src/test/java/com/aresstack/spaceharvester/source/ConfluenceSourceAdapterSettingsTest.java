package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfluenceSourceAdapterSettingsTest {

    private static final URI BASE = URI.create("https://confluence.example.org/wiki");

    @Test
    void foreignFileRedirectsAreOffUnlessTheSourceEnablesThem() {
        assertThat(ConfluenceSourceAdapter.settings(new SourceSettingsConfig("wiki", "confluence", null, BASE, null,
                Map.of())).get("foreignFileRedirects")).isEqualTo("false");
        assertThat(ConfluenceSourceAdapter.settings(new SourceSettingsConfig("wiki", "confluence", null, BASE, null,
                Map.of("foreignFileRedirects", "true"))).get("foreignFileRedirects")).isEqualTo("true");
    }
}

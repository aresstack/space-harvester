package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.proasteion.application.config.BrowserAccessConfig;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserSettingsTest {

    private final Map<String, String> values = new HashMap<>();
    private final BrowserSettings settings = new BrowserSettings(new BrowserSettings.Store() {
        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void putAll(Map<String, String> changes) {
            values.putAll(changes);
        }
    });

    @Test
    void withoutValuesExternBlocksAndSearchesWithDuckDuckGoAndInternHasNoSearch() {
        BrowserAccessConfig config = settings.load();
        assertThat(config.profile(BrowserProfile.EXTERNAL).allowByDefault()).isFalse();
        assertThat(config.profile(BrowserProfile.EXTERNAL).searchUrls())
                .containsExactly(BrowserSettings.DEFAULT_EXTERNAL_SEARCH);
        assertThat(config.profile(BrowserProfile.INTERNAL).searchUrls()).isEmpty();
    }

    @Test
    void aSavedProfileComesBackAndAlwaysAddsTheHostToTheActiveProfileOnce() {
        settings.save(BrowserProfile.INTERNAL, new BrowserAccessConfig(true, List.of("wiki.intra"),
                List.of("*.ads.example"), List.of("https://suche.intra/?q={query}"), null));
        settings.allow(BrowserProfile.EXTERNAL, "example.org");
        settings.allow(BrowserProfile.EXTERNAL, "example.org");
        settings.block(BrowserProfile.EXTERNAL, "tracker.example");

        BrowserAccessConfig config = settings.load();
        BrowserAccessConfig internal = config.profile(BrowserProfile.INTERNAL);
        assertThat(internal.allowByDefault()).isTrue();
        assertThat(internal.allow()).containsExactly("wiki.intra");
        assertThat(internal.block()).containsExactly("*.ads.example");
        assertThat(internal.searchUrls()).containsExactly("https://suche.intra/?q={query}");
        assertThat(config.profile(BrowserProfile.EXTERNAL).allow()).containsExactly("example.org");
        assertThat(config.profile(BrowserProfile.EXTERNAL).block()).containsExactly("tracker.example");
        assertThat(values).containsKey("browser.access.internal.mode").containsKey("browser.search.internal.urls");
    }

    @Test
    void theProfileRuleNamesTheProfileAndItsSearchUrls() {
        assertThat(BrowserRules.browserProfile("Extern", List.of(BrowserSettings.DEFAULT_EXTERNAL_SEARCH), true))
                .contains("Aktives Browserprofil: Extern").contains(BrowserSettings.DEFAULT_EXTERNAL_SEARCH)
                .contains("browser_navigate").contains("browser_search");
        assertThat(BrowserRules.browserProfile("Intern", List.of(), false)).contains("keine Such-URLs")
                .doesNotContain("browser_search");
    }
}

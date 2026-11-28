package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
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
    void theProfileRuleNamesTheProfileAndSearchesInTheSearchBrowser() {
        assertThat(BrowserRules.browserProfile(BrowserProfile.EXTERNAL)).contains("Aktives Browserprofil: Extern")
                .contains("browser_navigate").contains("browser_search");
    }

    @Test
    void theInternRuleSaysTheSearchCoversTheIntranetWithoutAnAddress() {
        // Angelo 28.11.: „im Intranet nach Reisekosten suchen“ – das Modell suchte eine Intranet-Adresse und gab auf.
        assertThat(BrowserRules.browserProfile(BrowserProfile.INTERNAL)).contains("Aktives Browserprofil: Intern")
                .contains("browser_search durchsucht in diesem Profil die Suchseiten des Hauses, also das Intranet")
                .contains("eine Intranet-Adresse oder Zugangsdaten brauchst du dafür nicht")
                .contains("auch wenn die Aufgabe keine Adresse nennt")
                .doesNotContain("freie Websuche");
    }

    @Test
    void withoutAStoredListExternSearchesStartpageAndDuckDuckGoAndInternNothing() {
        BrowserSearchEngineSelection external = settings.engines(BrowserProfile.EXTERNAL);
        assertThat(external.getEntries()).filteredOn(BrowserSearchEngineSelection.Entry::isEnabled)
                .extracting(BrowserSearchEngineSelection.Entry::getEngineId)
                .containsExactlyInAnyOrder(BrowserSearchEngineCatalog.STARTPAGE, BrowserSearchEngineCatalog.DUCKDUCKGO);
        assertThat(settings.engines(BrowserProfile.INTERNAL).getEntries()).isEmpty();
    }

    @Test
    void anEarlierSearchUrlOfTheProfileBecomesASearchPage() {
        values.put("browser.search.internal.urls", "https://suche.intra/?q={query}");

        assertThat(settings.engines(BrowserProfile.INTERNAL).getEntries())
                .extracting(BrowserSearchEngineSelection.Entry::getEngineId)
                .containsExactly("https://suche.intra/?q={query}");
    }

    @Test
    void searchPagesAreAddedOnceWithTheFirstPriorityAndComeBack() {
        settings.addSearchPage(BrowserProfile.INTERNAL, "https://portal.example/suche?q={query}&a=1,2");
        settings.addSearchPage(BrowserProfile.INTERNAL, "https://wiki.example/find?text={query}");
        settings.addSearchPage(BrowserProfile.INTERNAL, "https://portal.example/suche?q={query}&a=1,2");

        BrowserSearchEngineSelection internal = settings.engines(BrowserProfile.INTERNAL);
        assertThat(internal.getEntries()).extracting(BrowserSearchEngineSelection.Entry::getEngineId)
                .containsExactly("https://portal.example/suche?q={query}&a=1,2",
                        "https://wiki.example/find?text={query}");
        assertThat(internal.getEntries()).extracting(BrowserSearchEngineSelection.Entry::getPriority)
                .containsOnly(0);
        assertThat(settings.engines(BrowserProfile.EXTERNAL).getEntries())
                .extracting(BrowserSearchEngineSelection.Entry::getEngineId)
                .doesNotContain("https://wiki.example/find?text={query}");
    }

    @Test
    void theSearchWindowIsShownUntilSwitchedOff() {
        assertThat(settings.searchWindowShown()).isTrue();
        settings.saveSearchWindowShown(false);
        assertThat(settings.searchWindowShown()).isFalse();
    }
}

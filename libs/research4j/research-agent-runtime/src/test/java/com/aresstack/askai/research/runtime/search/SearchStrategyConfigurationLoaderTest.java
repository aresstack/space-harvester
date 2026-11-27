package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The snapshot loader carries only the SELECTION. A legacy {@code provider_settings} object is tolerated
 * syntactically but never turned into configuration, and the type no longer exposes any credential surface.
 */
public final class SearchStrategyConfigurationLoaderTest {

    @Test
    public void selectionOnlySnapshotParses() {
        SearchStrategyConfiguration config = SearchStrategyConfigurationLoader.parse(
                "{\"strategy\":\"API_PROVIDER\",\"provider\":\"BRAVE_SEARCH_API\","
                        + "\"engine\":\"BRAVE\",\"language\":\"de\",\"country\":\"DE\"}");
        assertEquals(StrategySelection.API_PROVIDER, config.getStrategy());
        assertEquals(SearchProviderId.BRAVE_SEARCH_API, config.getProviderId());
        assertEquals(SearchEngine.BRAVE, config.getEngine());
        assertEquals("de", config.getLanguage());
        assertEquals("DE", config.getCountry());
    }

    @Test
    public void legacyProviderSettingsAreAcceptedButIgnored() {
        // An old host snapshot with credentials must still LOAD (no hard error) — the values are dropped.
        SearchStrategyConfiguration config = SearchStrategyConfigurationLoader.parse(
                "{\"strategy\":\"API_PROVIDER\",\"provider\":\"DATA_FOR_SEO\",\"engine\":\"GOOGLE\","
                        + "\"provider_settings\":{\"login\":\"secret-user\",\"password\":\"secret-pass\"}}");
        assertEquals(StrategySelection.API_PROVIDER, config.getStrategy());
        assertEquals(SearchProviderId.DATA_FOR_SEO, config.getProviderId());
        // The configuration type exposes no way to read provider credentials at all.
        for (Method method : SearchStrategyConfiguration.class.getMethods()) {
            String name = method.getName().toLowerCase();
            if (name.contains("providersetting") || name.contains("credential")
                    || name.contains("secret") || name.contains("apikey") || name.contains("password")) {
                fail("SearchStrategyConfiguration must not expose credentials: " + method.getName());
            }
        }
    }

    @Test
    public void legacyBrowserSelectionNeedsNoProvider() {
        SearchStrategyConfiguration config =
                SearchStrategyConfigurationLoader.parse("{\"strategy\":\"LEGACY_BROWSER\"}");
        assertEquals(StrategySelection.LEGACY_BROWSER, config.getStrategy());
    }

    @Test
    public void anInvalidStrategyIsAHardError() {
        try {
            SearchStrategyConfigurationLoader.parse("{\"strategy\":\"NONSENSE\"}");
            fail("an unknown strategy must be rejected");
        } catch (SearchStrategyConfigurationLoader.InvalidConfigurationException expected) {
            // expected — never a silent fallback
        }
    }
}

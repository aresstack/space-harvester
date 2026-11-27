package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Contract lock for the HOST-written snapshot: these literals are byte-for-byte what the plugin's
 * {@code SearchStrategySelection.toSnapshotJson()} emits (see SearchStrategyWiringTest in
 * research-agent-ui-plugin — the modules deliberately share no code, only this JSON shape).
 */
public class HostSnapshotContractTest {

    @Test
    public void theFullHostSnapshotParses() {
        SearchStrategyConfiguration config = SearchStrategyConfigurationLoader.parse(
                "{ \"strategy\": \"API_PROVIDER\", \"provider\": \"DATA_FOR_SEO\", "
                        + "\"engine\": \"GOOGLE\", \"language\": \"de\", \"country\": \"de\" }");
        assertEquals(StrategySelection.API_PROVIDER, config.getStrategy());
        assertEquals(SearchProviderId.DATA_FOR_SEO, config.getProviderId());
        assertEquals(SearchEngine.GOOGLE, config.getEngine());
        assertEquals("de", config.getLanguage());
        assertEquals("de", config.getCountry());
    }

    @Test
    public void theMinimalHostSnapshotParsesWithProviderDefaultEngine() {
        SearchStrategyConfiguration config = SearchStrategyConfigurationLoader.parse(
                "{ \"strategy\": \"API_PROVIDER\", \"provider\": \"BRAVE_SEARCH_API\", "
                        + "\"engine\": \"PROVIDER_DEFAULT\" }");
        assertEquals(StrategySelection.API_PROVIDER, config.getStrategy());
        assertEquals(SearchProviderId.BRAVE_SEARCH_API, config.getProviderId());
        assertEquals(SearchEngine.PROVIDER_DEFAULT, config.getEngine());
        assertNull(config.getLanguage());
        assertNull(config.getCountry());
    }
}

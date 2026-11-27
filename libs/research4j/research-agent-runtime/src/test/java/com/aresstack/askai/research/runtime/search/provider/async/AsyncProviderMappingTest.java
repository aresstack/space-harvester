package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class AsyncProviderMappingTest {

    @Test
    public void moduleIdsMapToRuntimeIds() {
        assertEquals(SearchProviderId.BRAVE_SEARCH_API, AsyncProviderMapping.toRuntimeId(
                com.aresstack.askai.research.search.api.SearchProviderId.BRAVE));
        assertEquals(SearchProviderId.BRIGHT_DATA, AsyncProviderMapping.toRuntimeId(
                com.aresstack.askai.research.search.api.SearchProviderId.BRIGHT_DATA));
        assertEquals(SearchProviderId.DATA_FOR_SEO, AsyncProviderMapping.toRuntimeId(
                com.aresstack.askai.research.search.api.SearchProviderId.DATA_FOR_SEO));
        assertEquals(SearchProviderId.SERP_API, AsyncProviderMapping.toRuntimeId(
                com.aresstack.askai.research.search.api.SearchProviderId.SERP_API));
    }

    @Test
    public void moduleOnlyIdsHaveNoRuntimeCounterpart() {
        assertNull(AsyncProviderMapping.toRuntimeId(
                com.aresstack.askai.research.search.api.SearchProviderId.COMPOSITE));
        assertNull(AsyncProviderMapping.toRuntimeId(null));
    }

    @Test
    public void migratedProvidersHaveADefaultEngine() {
        assertEquals(SearchEngine.BRAVE, AsyncProviderMapping.defaultEngine(SearchProviderId.BRAVE_SEARCH_API));
        assertEquals(SearchEngine.PROVIDER_DEFAULT, AsyncProviderMapping.defaultEngine(SearchProviderId.BRIGHT_DATA));
        assertEquals(SearchEngine.PROVIDER_DEFAULT, AsyncProviderMapping.defaultEngine(SearchProviderId.DATA_FOR_SEO));
        assertEquals(SearchEngine.PROVIDER_DEFAULT, AsyncProviderMapping.defaultEngine(SearchProviderId.SERP_API));
        assertNull(AsyncProviderMapping.defaultEngine(SearchProviderId.TAVILY));
    }
}

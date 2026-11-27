package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;

import com.aresstack.askai.research.search.application.WebSearchProvidersModule;
import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The productive factory builds a generation of {@link AsyncModuleSearchProvider} adapters from the
 * file-based module config (no HTTP: constructing providers never calls out). Verifies id/enum mapping and
 * per-provider tolerance in the real open path.
 */
public final class ModuleAsyncSearchGenerationFactoryTest {

    private static void enableAll(ProviderConfigurationPaths paths) {
        WebSearchProvidersModule setup = WebSearchProvidersModule.open(paths);
        try {
            ProviderConfigurationService service = setup.getConfigurationService();
            BraveSearchConfiguration brave = service.loadOrCreateBrave();
            brave.setEnabled(true);
            service.saveBrave(brave, "brave-secret".toCharArray());
            BrightDataSearchConfiguration brightData = service.loadOrCreateBrightData();
            brightData.setEnabled(true);
            brightData.setZone("test-zone");
            service.saveBrightData(brightData, "bd-secret".toCharArray());
            DataForSeoSearchConfiguration dataForSeo = service.loadOrCreateDataForSeo();
            dataForSeo.setEnabled(true);
            service.saveDataForSeo(dataForSeo, "dfs-secret".toCharArray());
        } finally {
            setup.close();
        }
    }

    @Test
    public void buildsAdaptersForEveryEnabledProvider() throws Exception {
        Path directory = Files.createTempDirectory("wsp-factory");
        ProviderConfigurationPaths paths = new ProviderConfigurationPaths(directory);
        enableAll(paths);

        AsyncSearchGeneration generation =
                new ModuleAsyncSearchGenerationFactory(paths, 5_000).open();
        try {
            Set<SearchProviderId> ids = generation.availableProviderIds();
            assertEquals(3, ids.size());
            assertTrue(ids.contains(SearchProviderId.BRAVE_SEARCH_API));
            assertTrue(ids.contains(SearchProviderId.BRIGHT_DATA));
            assertTrue(ids.contains(SearchProviderId.DATA_FOR_SEO));

            SearchProvider brave = generation.provider(SearchProviderId.BRAVE_SEARCH_API);
            assertTrue(brave instanceof AsyncModuleSearchProvider);
            assertEquals(SearchProviderId.BRAVE_SEARCH_API, brave.getProviderId());
        } finally {
            generation.close();
            generation.close(); // idempotent
        }
    }

    @Test
    public void aDisabledProviderIsAbsentFromTheGeneration() throws Exception {
        Path directory = Files.createTempDirectory("wsp-factory-partial");
        ProviderConfigurationPaths paths = new ProviderConfigurationPaths(directory);
        // Only Bright Data enabled; Brave/DataForSEO stay disabled defaults.
        WebSearchProvidersModule setup = WebSearchProvidersModule.open(paths);
        try {
            BrightDataSearchConfiguration brightData = setup.getConfigurationService().loadOrCreateBrightData();
            brightData.setEnabled(true);
            brightData.setZone("test-zone");
            setup.getConfigurationService().saveBrightData(brightData, "bd-secret".toCharArray());
        } finally {
            setup.close();
        }

        AsyncSearchGeneration generation =
                new ModuleAsyncSearchGenerationFactory(paths, 5_000).open();
        try {
            assertEquals(1, generation.availableProviderIds().size());
            assertTrue(generation.availableProviderIds().contains(SearchProviderId.BRIGHT_DATA));
            assertNull(generation.provider(SearchProviderId.BRAVE_SEARCH_API));
        } finally {
            generation.close();
        }
    }

    @Test
    public void noProviderFilesStillOpenButEverySelectedProviderFailsTyped() throws Exception {
        Path directory = Files.createTempDirectory("wsp-none");
        ProviderConfigurationPaths paths = new ProviderConfigurationPaths(directory);

        // No configured provider must NOT prevent opening the registry (the agent must still start).
        AsyncSearchProviderRegistry registry =
                new AsyncSearchProviderRegistry(new ModuleAsyncSearchGenerationFactory(paths, 5_000));
        try {
            SearchProvider brave = registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API);
            assertEquals(SearchProviderAvailability.NOT_CONFIGURED, brave.getAvailability());
            try {
                brave.search(new SearchProviderRequest("q", SearchEngine.BRAVE, 10, "de", "DE"));
                fail("a selected-but-unconfigured provider must fail typed");
            } catch (SearchProviderConfigurationException expected) {
                assertEquals(SearchProviderId.BRAVE_SEARCH_API, expected.getProviderId());
            }
        } finally {
            registry.close();
        }
    }
}

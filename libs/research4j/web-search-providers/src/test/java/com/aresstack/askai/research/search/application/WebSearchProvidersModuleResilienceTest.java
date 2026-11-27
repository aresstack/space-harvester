package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Resilient open: one broken provider file must not take the others down. A whole-module failure still
 * throws; a single invalid provider is simply omitted. (Lifecycle, not an HTTP contract.)
 */
public final class WebSearchProvidersModuleResilienceTest {

    @Test
    public void oneInvalidProviderFileDisablesOnlyThatProvider() throws Exception {
        Path directory = Files.createTempDirectory("wsp-resilience");
        ProviderConfigurationPaths paths = new ProviderConfigurationPaths(directory);

        // Enable Bright Data + DataForSEO with encrypted secrets via a first (strict) open.
        WebSearchProvidersModule setup = WebSearchProvidersModule.open(paths);
        ProviderConfigurationService service = setup.getConfigurationService();
        BrightDataSearchConfiguration brightData = service.loadOrCreateBrightData();
        brightData.setEnabled(true);
        brightData.setZone("test-zone");
        service.saveBrightData(brightData, "bd-secret".toCharArray());
        DataForSeoSearchConfiguration dataForSeo = service.loadOrCreateDataForSeo();
        dataForSeo.setEnabled(true);
        service.saveDataForSeo(dataForSeo, "dfs-secret".toCharArray());
        setup.close();

        // Corrupt brave.json so its load fails.
        Files.write(paths.getBraveConfigurationFile(),
                "{ not valid json".getBytes(StandardCharsets.UTF_8));

        // Strict open must fail because of the broken Brave file...
        try {
            WebSearchProvidersModule.open(paths).close();
            fail("strict open must fail on a broken provider file");
        } catch (RuntimeException expected) {
            // expected
        }

        // ...but the resilient open omits Brave and keeps the other two usable.
        WebSearchProvidersModule module = WebSearchProvidersModule.openResilient(paths);
        try {
            WebSearchProviderRegistry registry = module.getProviderRegistry();
            assertEquals("only the two valid providers are present", 2, registry.getAll().size());
            assertNotNull(registry.require(SearchProviderId.BRIGHT_DATA));
            assertNotNull(registry.require(SearchProviderId.DATA_FOR_SEO));
            try {
                registry.require(SearchProviderId.BRAVE);
                fail("Brave must be absent after a failed load");
            } catch (RuntimeException braveAbsent) {
                assertTrue(true);
            }
        } finally {
            module.close();
        }
    }
}

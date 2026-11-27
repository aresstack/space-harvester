package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchEngine;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Lifecycle smoke for the imported async web-search-provider module: open a module against a scratch
 * provider directory, enable Brave / Bright Data / DataForSEO with secrets, and verify the registry then
 * carries all three, that no plaintext secret is persisted (AES-GCM at rest), and that close() shuts the
 * AsyncHttpClient-backed providers down without error. It performs NO live HTTP calls.
 */
public final class WebSearchProvidersModuleSmokeTest {

    @Test
    public void openEnablesProvidersEncryptsSecretsAndClosesTheHttpClient() throws Exception {
        Path directory = Files.createTempDirectory("wsp-module-smoke");
        ProviderConfigurationPaths paths = new ProviderConfigurationPaths(directory);

        WebSearchProvidersModule module = WebSearchProvidersModule.open(paths);
        try {
            // open() seeds default, DISABLED configs, so the registry starts empty.
            assertNotNull(module.getConfigurationService());
            assertTrue("a fresh provider directory yields no enabled providers",
                    module.getProviderRegistry().getAll().isEmpty());

            ProviderConfigurationService service = module.getConfigurationService();

            BraveSearchConfiguration brave = service.loadOrCreateBrave();
            brave.setEnabled(true);
            service.saveBrave(brave, "brave-secret-PLAINTEXT".toCharArray());

            BrightDataSearchConfiguration brightData = service.loadOrCreateBrightData();
            brightData.setEnabled(true);
            brightData.setZone("test-zone");
            service.saveBrightData(brightData, "brightdata-secret-PLAINTEXT".toCharArray());

            DataForSeoSearchConfiguration dataForSeo = service.loadOrCreateDataForSeo();
            dataForSeo.setEnabled(true);
            service.saveDataForSeo(dataForSeo, "dataforseo-secret-PLAINTEXT".toCharArray());

            SerpApiSearchConfiguration serpApi = service.loadOrCreateSerpApi();
            serpApi.setEnabled(true);
            serpApi.setSearchEngine(SerpApiSearchEngine.BING);
            service.saveSerpApi(serpApi, "serpapi-secret-PLAINTEXT".toCharArray());

            // Rebuild the registry from the now-enabled, catalog-validated configurations.
            module.reloadProviders();
            WebSearchProviderRegistry registry = module.getProviderRegistry();
            assertNotNull(registry.require(SearchProviderId.BRAVE));
            assertNotNull(registry.require(SearchProviderId.BRIGHT_DATA));
            assertNotNull(registry.require(SearchProviderId.DATA_FOR_SEO));
            assertNotNull(registry.require(SearchProviderId.SERP_API));
            assertTrue(registry.require(SearchProviderId.SERP_API).supports(SearchEngine.BING));
            assertEquals(4, registry.getAll().size());

            // Secrets are encrypted at rest: no config file leaks the plaintext, each stores ciphertext.
            Path[] files = {paths.getBraveConfigurationFile(), paths.getBrightDataConfigurationFile(),
                    paths.getDataForSeoConfigurationFile(), paths.getSerpApiConfigurationFile()};
            for (Path file : files) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse(file + " must not persist a plaintext secret", json.contains("secret-PLAINTEXT"));
                assertTrue(file + " must persist an encrypted secret", json.contains("cipherText"));
            }
            assertTrue("the AES-GCM key file is created", Files.exists(paths.getSecretKeyFile()));
        } finally {
            // Shuts down every provider's AsyncHttpClient; must complete without throwing.
            module.close();
        }
    }
}

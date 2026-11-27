package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.config.GsonProviderConfigurationStore;
import com.aresstack.askai.research.search.config.ProviderConfigurationBundle;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;
import com.aresstack.askai.research.search.config.ProviderConfigurationService;
import com.aresstack.askai.research.search.http.AsyncHttpClientFactory;
import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

public final class WebSearchProvidersModule
        implements AutoCloseable {

    private final ProviderConfigurationService configurationService;
    private final SearchProviderFactory providerFactory;
    private final boolean resilient;
    private WebSearchProviderRegistry providerRegistry;

    private WebSearchProvidersModule(
            ProviderConfigurationService configurationService,
            SearchProviderFactory providerFactory,
            boolean resilient) {

        this.configurationService = configurationService;
        this.providerFactory = providerFactory;
        this.resilient = resilient;
        this.providerRegistry = createRegistry(loadBundle());
    }

    public static WebSearchProvidersModule openUserHome() {
        return open(ProviderConfigurationPaths.userHome());
    }

    /** Strict open: any invalid provider configuration fails the whole open. */
    public static WebSearchProvidersModule open(
            ProviderConfigurationPaths paths) {

        return create(paths, false);
    }

    /**
     * Resilient open: a whole-module failure (the provider directory or the AES-GCM key store cannot be
     * used) still throws, but a single invalid or unloadable provider is simply omitted from the registry
     * instead of failing the others. Used by hosts that hot-reload provider settings.
     */
    public static WebSearchProvidersModule openResilient(
            ProviderConfigurationPaths paths) {

        return create(paths, true);
    }

    private static WebSearchProvidersModule create(
            ProviderConfigurationPaths paths, boolean resilient) {

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .disableHtmlEscaping()
                .create();

        SecretValueService secretValueService =
                new SecretValueService(
                        new AesGcmSecretCipher(
                                new FileSecretKeyProvider(
                                        paths.getSecretKeyFile())));

        ProviderConfigurationService configurationService =
                new ProviderConfigurationService(
                        paths,
                        new GsonProviderConfigurationStore(gson),
                        secretValueService);

        SearchProviderFactory factory =
                new SearchProviderFactory(
                        gson,
                        new AsyncHttpClientFactory(),
                        secretValueService);

        return new WebSearchProvidersModule(
                configurationService,
                factory,
                resilient);
    }

    public ProviderConfigurationService
            getConfigurationService() {

        return configurationService;
    }

    public synchronized WebSearchProviderRegistry
            getProviderRegistry() {

        return providerRegistry;
    }

    public synchronized void reloadProviders() {
        WebSearchProviderRegistry replacement = createRegistry(loadBundle());
        WebSearchProviderRegistry previous = providerRegistry;
        providerRegistry = replacement;
        previous.close();
    }

    /** In resilient mode, an invalid single provider config falls back to a disabled default (skipped). */
    private ProviderConfigurationBundle loadBundle() {
        if (!resilient) {
            return configurationService.loadOrCreateAll();
        }
        return new ProviderConfigurationBundle(loadBraveOrDisabled(),
                loadBrightDataOrDisabled(), loadDataForSeoOrDisabled(), loadSerpApiOrDisabled());
    }

    private com.aresstack.askai.research.search.brave.BraveSearchConfiguration loadBraveOrDisabled() {
        try {
            return configurationService.loadOrCreateBrave();
        } catch (RuntimeException invalid) {
            return new com.aresstack.askai.research.search.brave.BraveSearchConfiguration();
        }
    }

    private com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration
            loadBrightDataOrDisabled() {
        try {
            return configurationService.loadOrCreateBrightData();
        } catch (RuntimeException invalid) {
            return new com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration();
        }
    }

    private com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration
            loadDataForSeoOrDisabled() {
        try {
            return configurationService.loadOrCreateDataForSeo();
        } catch (RuntimeException invalid) {
            return new com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration();
        }
    }

    private com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration loadSerpApiOrDisabled() {
        try {
            return configurationService.loadOrCreateSerpApi();
        } catch (RuntimeException invalid) {
            return new com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration();
        }
    }

    private WebSearchProviderRegistry createRegistry(
            ProviderConfigurationBundle configurations) {

        List<WebSearchProvider> providers =
                new ArrayList<WebSearchProvider>();

        if (configurations.getBrave().isEnabled()) {
            addProvider(providers, new ProviderSupplier() {
                public WebSearchProvider get() {
                    return providerFactory.createBrave(configurations.getBrave());
                }
            });
        }
        if (configurations.getBrightData().isEnabled()) {
            addProvider(providers, new ProviderSupplier() {
                public WebSearchProvider get() {
                    return providerFactory.createBrightData(configurations.getBrightData());
                }
            });
        }
        if (configurations.getDataForSeo().isEnabled()) {
            addProvider(providers, new ProviderSupplier() {
                public WebSearchProvider get() {
                    return providerFactory.createDataForSeo(configurations.getDataForSeo());
                }
            });
        }

        if (configurations.getSerpApi().isEnabled()) {
            addProvider(providers, new ProviderSupplier() {
                public WebSearchProvider get() {
                    return providerFactory.createSerpApi(configurations.getSerpApi());
                }
            });
        }

        return new WebSearchProviderRegistry(providers);
    }

    /** Builds and adds one provider; in resilient mode a failed build is skipped instead of propagated. */
    private void addProvider(List<WebSearchProvider> providers, ProviderSupplier supplier) {
        if (resilient) {
            try {
                providers.add(supplier.get());
            } catch (RuntimeException | LinkageError unusable) {
                // A single unusable provider (bad config, or a transport that cannot load, e.g. a classpath
                // LinkageError) must not abort the whole session start: skip it and say why.
                System.err.println("[web-search] search provider skipped, it could not be created: " + unusable);
            }
        } else {
            providers.add(supplier.get());
        }
    }

    private interface ProviderSupplier {
        WebSearchProvider get();
    }

    @Override
    public synchronized void close() {
        providerRegistry.close();
    }
}

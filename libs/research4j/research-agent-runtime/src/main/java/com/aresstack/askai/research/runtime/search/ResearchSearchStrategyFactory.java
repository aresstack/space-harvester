package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRegistry;

/**
 * The single place that turns a session {@link SearchStrategyConfiguration} into a {@link SearchStrategy},
 * using an INJECTED {@link SearchProviderRegistry}. The registry — with its provider credentials (loaded
 * from the provider files) and AsyncHttpClients — is a runtime-scoped resource owned by the agent; this
 * factory never builds a provider from the snapshot, so no secret ever reaches it. For {@link
 * StrategySelection#LEGACY_BROWSER} it returns {@code null} ("keep the loop's unchanged default browser
 * strategy"); for {@link StrategySelection#API_PROVIDER} it resolves the configured provider from the
 * registry and wraps it in a {@link SingleProviderSearchStrategy}.
 */
public final class ResearchSearchStrategyFactory {

    private ResearchSearchStrategyFactory() {
    }

    public static SearchStrategy create(SearchStrategyConfiguration config,
                                        SearchProviderRegistry registry) {
        if (config.getStrategy() == StrategySelection.LEGACY_BROWSER) {
            return null;
        }
        if (registry == null) {
            throw new IllegalArgumentException("an API_PROVIDER strategy requires a provider registry");
        }
        SearchProvider provider = registry.requireImplementedProvider(config.getProviderId());
        return new SingleProviderSearchStrategy(provider, config.getEngine());
    }
}

package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

/**
 * The session-start snapshot of the initial-search SELECTION only: which {@link StrategySelection} is active
 * and, for {@link StrategySelection#API_PROVIDER}, which {@link SearchProviderId}/{@link SearchEngine} plus
 * the non-secret locale hints. It deliberately carries NO provider credentials — API keys, usernames and
 * passwords live exclusively in the provider files under {@code ${user.home}/agents/research/providers} and
 * never travel through this snapshot, agent arguments or the process environment. A running research session
 * keeps this snapshot for its whole lifetime — the strategy is fixed once at start.
 */
public final class SearchStrategyConfiguration {

    private final StrategySelection strategy;
    private final SearchProviderId providerId;
    private final SearchEngine engine;
    private final String language;
    private final String country;

    public SearchStrategyConfiguration(StrategySelection strategy, SearchProviderId providerId,
                                       SearchEngine engine, String language, String country) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        if (strategy == StrategySelection.API_PROVIDER && (providerId == null || engine == null)) {
            throw new IllegalArgumentException(
                    "API_PROVIDER strategy requires a provider id and a search engine");
        }
        this.strategy = strategy;
        this.providerId = providerId;
        this.engine = engine;
        this.language = language;
        this.country = country;
    }

    /** The legacy-browser selection with no provider — the loop keeps its unchanged default strategy. */
    public static SearchStrategyConfiguration legacyBrowser() {
        return new SearchStrategyConfiguration(StrategySelection.LEGACY_BROWSER, null, null, null, null);
    }

    public StrategySelection getStrategy() {
        return strategy;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getEngine() {
        return engine;
    }

    public String getLanguage() {
        return language;
    }

    public String getCountry() {
        return country;
    }
}

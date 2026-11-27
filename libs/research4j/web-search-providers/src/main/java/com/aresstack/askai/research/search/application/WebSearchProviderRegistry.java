package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class WebSearchProviderRegistry
        implements AutoCloseable {

    private final Map<SearchProviderId, WebSearchProvider> providers;

    public WebSearchProviderRegistry(
            List<WebSearchProvider> providers) {

        if (providers == null) {
            throw new IllegalArgumentException(
                    "providers must not be null");
        }

        this.providers =
                new EnumMap<SearchProviderId, WebSearchProvider>(
                        SearchProviderId.class);
        for (WebSearchProvider provider : providers) {
            register(provider);
        }
    }

    public WebSearchProvider require(
            SearchProviderId providerId) {

        WebSearchProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Search provider is not configured: "
                            + providerId);
        }
        return provider;
    }

    public List<WebSearchProvider> getAll() {
        return Collections.unmodifiableList(
                new ArrayList<WebSearchProvider>(
                        providers.values()));
    }

    private void register(WebSearchProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException(
                    "providers must not contain null entries");
        }
        if (providers.put(provider.getProviderId(), provider) != null) {
            throw new IllegalArgumentException(
                    "Duplicate search provider: "
                            + provider.getProviderId());
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (WebSearchProvider provider : providers.values()) {
            try {
                provider.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

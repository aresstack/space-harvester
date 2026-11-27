package com.aresstack.askai.research.search.application;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class CompositeWebSearchProvider
        implements WebSearchProvider {

    private final List<WebSearchProvider> providers;
    private final boolean toleratePartialFailure;
    private final boolean ownsProviders;

    public CompositeWebSearchProvider(
            List<WebSearchProvider> providers,
            boolean toleratePartialFailure) {

        this(
                providers,
                toleratePartialFailure,
                false);
    }

    private CompositeWebSearchProvider(
            List<WebSearchProvider> providers,
            boolean toleratePartialFailure,
            boolean ownsProviders) {

        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException(
                    "providers must not be empty");
        }
        this.providers =
                new ArrayList<WebSearchProvider>(providers);
        this.toleratePartialFailure = toleratePartialFailure;
        this.ownsProviders = ownsProviders;
    }

    public static CompositeWebSearchProvider owning(
            List<WebSearchProvider> providers,
            boolean toleratePartialFailure) {

        return new CompositeWebSearchProvider(
                providers,
                toleratePartialFailure,
                true);
    }

    @Override
    public SearchProviderId getProviderId() {
        return SearchProviderId.COMPOSITE;
    }

    @Override
    public boolean supports(SearchEngine searchEngine) {
        for (WebSearchProvider provider : providers) {
            if (provider.supports(searchEngine)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<WebSearchResult> search(
            WebSearchRequest request) {

        final List<CompletableFuture<WebSearchResult>> futures =
                new ArrayList<CompletableFuture<WebSearchResult>>();

        for (WebSearchProvider provider : providers) {
            CompletableFuture<WebSearchResult> future =
                    provider.search(request);
            if (toleratePartialFailure) {
                future = future.exceptionally(
                        new Function<Throwable, WebSearchResult>() {
                            @Override
                            public WebSearchResult apply(
                                    Throwable failure) {
                                return null;
                            }
                        });
            }
            futures.add(future);
        }

        CompletableFuture<?>[] array = futures.toArray(
                new CompletableFuture<?>[futures.size()]);

        return CompletableFuture.allOf(array)
                .thenApply(new Function<Void, WebSearchResult>() {
                    @Override
                    public WebSearchResult apply(Void ignored) {
                        return merge(futures);
                    }
                });
    }

    private WebSearchResult merge(
            List<CompletableFuture<WebSearchResult>> futures) {

        Map<String, WebSearchHit> unique =
                new LinkedHashMap<String, WebSearchHit>();
        int successfulProviders = 0;

        for (CompletableFuture<WebSearchResult> future : futures) {
            WebSearchResult result = future.join();
            if (result == null) {
                continue;
            }
            successfulProviders++;
            for (WebSearchHit hit : result.getHits()) {
                String canonicalUrl = canonicalize(hit.getUrl());
                if (!unique.containsKey(canonicalUrl)) {
                    unique.put(canonicalUrl, hit);
                }
            }
        }

        if (successfulProviders == 0) {
            throw new WebSearchException(
                    "All configured search providers failed");
        }

        return new WebSearchResult(
                SearchProviderId.COMPOSITE,
                SearchEngine.MULTI,
                new ArrayList<WebSearchHit>(unique.values()),
                "");
    }

    private String canonicalize(String url) {
        try {
            URI uri = new URI(url);
            String scheme = lower(uri.getScheme());
            String host = lower(uri.getHost());
            int port = uri.getPort();
            String path = uri.getPath();
            // The query string is part of the identity: "?id=1" and "?id=2" are different documents.
            // Only the fragment is dropped, since it never reaches the server.
            String query = uri.getRawQuery();

            if (path == null || path.isEmpty()) {
                path = "/";
            }
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            boolean defaultPort = port == -1
                    || ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);

            return scheme
                    + "://"
                    + host
                    + (defaultPort ? "" : ":" + port)
                    + path
                    + (query == null || query.isEmpty() ? "" : "?" + query);
        } catch (URISyntaxException exception) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
    }

    private String lower(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        if (!ownsProviders) {
            return;
        }

        RuntimeException failure = null;
        for (WebSearchProvider provider : providers) {
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

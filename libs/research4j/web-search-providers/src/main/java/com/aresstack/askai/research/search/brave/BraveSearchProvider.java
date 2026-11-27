package com.aresstack.askai.research.search.brave;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.http.AsyncJsonHttpClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class BraveSearchProvider
        implements WebSearchProvider {

    private final BraveSearchConfiguration configuration;
    private final AsyncJsonHttpClient httpClient;
    private final BraveSearchRequestMapper requestMapper;
    private final BraveSearchResponseMapper responseMapper;

    public BraveSearchProvider(
            BraveSearchConfiguration configuration,
            AsyncJsonHttpClient httpClient,
            BraveSearchRequestMapper requestMapper,
            BraveSearchResponseMapper responseMapper) {

        this.configuration = requireNonNull(
                configuration,
                "configuration");
        this.httpClient = requireNonNull(
                httpClient,
                "httpClient");
        this.requestMapper = requireNonNull(
                requestMapper,
                "requestMapper");
        this.responseMapper = requireNonNull(
                responseMapper,
                "responseMapper");
    }

    @Override
    public SearchProviderId getProviderId() {
        return SearchProviderId.BRAVE;
    }

    @Override
    public boolean supports(SearchEngine searchEngine) {
        return SearchEngine.BRAVE == searchEngine;
    }

    @Override
    public CompletableFuture<WebSearchResult> search(
            WebSearchRequest request) {

        String url = requestMapper.createUrl(
                configuration,
                request);

        return httpClient.get(
                url,
                requestMapper.createHeaders(configuration))
                .thenApply(new Function<String, WebSearchResult>() {
                    @Override
                    public WebSearchResult apply(String response) {
                        return responseMapper.map(response);
                    }
                });
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}

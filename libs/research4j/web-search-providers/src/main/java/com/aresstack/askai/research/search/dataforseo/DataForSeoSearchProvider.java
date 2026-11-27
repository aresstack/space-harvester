package com.aresstack.askai.research.search.dataforseo;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.http.AsyncJsonHttpClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class DataForSeoSearchProvider
        implements WebSearchProvider {

    private final DataForSeoSearchConfiguration configuration;
    private final AsyncJsonHttpClient httpClient;
    private final DataForSeoRequestMapper requestMapper;
    private final DataForSeoSearchResponseMapper responseMapper;

    public DataForSeoSearchProvider(
            DataForSeoSearchConfiguration configuration,
            AsyncJsonHttpClient httpClient,
            DataForSeoRequestMapper requestMapper,
            DataForSeoSearchResponseMapper responseMapper) {

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
        return SearchProviderId.DATA_FOR_SEO;
    }

    @Override
    public boolean supports(SearchEngine searchEngine) {
        return configuration.getSearchEngine() != null
                && configuration.getSearchEngine()
                .getSearchEngine() == searchEngine;
    }

    @Override
    public CompletableFuture<WebSearchResult> search(
            WebSearchRequest request) {

        String endpoint = requestMapper.createEndpoint(
                configuration);
        String body = requestMapper.createBody(
                configuration,
                request);

        return httpClient.postJson(endpoint, body)
                .thenApply(new Function<String, WebSearchResult>() {
                    @Override
                    public WebSearchResult apply(String response) {
                        return responseMapper.map(
                                response,
                                configuration.getSearchEngine()
                                        .getSearchEngine());
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

package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.http.AsyncJsonHttpClient;
import com.aresstack.askai.research.search.http.UrlQueryBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class BrightDataSearchProvider
        implements WebSearchProvider {

    private final BrightDataSearchConfiguration configuration;
    private final AsyncJsonHttpClient httpClient;
    private final BrightDataRequestMapper requestMapper;
    private final BrightDataSearchResponseMapper responseMapper;
    private final ScheduledExecutorService pollingExecutor;

    public BrightDataSearchProvider(
            BrightDataSearchConfiguration configuration,
            AsyncJsonHttpClient httpClient,
            BrightDataRequestMapper requestMapper,
            BrightDataSearchResponseMapper responseMapper) {

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
        this.pollingExecutor =
                Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public SearchProviderId getProviderId() {
        return SearchProviderId.BRIGHT_DATA;
    }

    @Override
    public boolean supports(SearchEngine searchEngine) {
        return configuration.getSearchEngine() != null
                && configuration.getSearchEngine()
                .getSearchEngine() == searchEngine;
    }

    @Override
    public CompletableFuture<WebSearchResult> search(
            final WebSearchRequest request) {

        if (configuration.getExecutionMode()
                == BrightDataExecutionMode.ASYNCHRONOUS) {
            return executeAsynchronously(request);
        }
        return executeSynchronously(request);
    }

    private CompletableFuture<WebSearchResult>
            executeSynchronously(WebSearchRequest request) {

        String body = requestMapper.createSynchronousBody(
                configuration,
                request);

        return httpClient.postJson(
                configuration.getSynchronousEndpoint(),
                body)
                .thenApply(new Function<String, WebSearchResult>() {
                    @Override
                    public WebSearchResult apply(String response) {
                        return responseMapper.map(
                                response,
                                configuredEngine());
                    }
                });
    }

    private CompletableFuture<WebSearchResult>
            executeAsynchronously(final WebSearchRequest request) {

        String endpoint = createAsyncRequestEndpoint();
        String body = requestMapper.createAsynchronousBody(
                configuration,
                request);

        return httpClient.postJson(endpoint, body)
                .thenCompose(
                        new Function<String,
                                CompletableFuture<WebSearchResult>>() {
                            @Override
                            public CompletableFuture<WebSearchResult> apply(
                                    String response) {

                                String responseId =
                                        responseMapper.extractResponseId(
                                                response);
                                CompletableFuture<WebSearchResult> result =
                                        new CompletableFuture<WebSearchResult>();
                                schedulePoll(responseId, 1, result);
                                return result;
                            }
                        });
    }

    private void schedulePoll(
            final String responseId,
            final int attempt,
            final CompletableFuture<WebSearchResult> result) {

        pollingExecutor.schedule(
                new Runnable() {
                    @Override
                    public void run() {
                        poll(responseId, attempt, result);
                    }
                },
                configuration.getPollingIntervalMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void poll(
            final String responseId,
            final int attempt,
            final CompletableFuture<WebSearchResult> result) {

        if (result.isDone()) {
            return;
        }

        if (attempt > configuration.getMaximumPollAttempts()) {
            result.completeExceptionally(
                    new WebSearchException(
                            "Bright Data async result did not become ready"));
            return;
        }

        httpClient.get(createAsyncResultEndpoint(responseId))
                .whenComplete(new BiConsumer<String, Throwable>() {
                    @Override
                    public void accept(
                            String response,
                            Throwable failure) {

                        if (failure != null) {
                            result.completeExceptionally(failure);
                            return;
                        }

                        if (isPending(response)) {
                            schedulePoll(
                                    responseId,
                                    attempt + 1,
                                    result);
                            return;
                        }

                        try {
                            result.complete(responseMapper.map(
                                    response,
                                    configuredEngine()));
                        } catch (RuntimeException exception) {
                            result.completeExceptionally(exception);
                        }
                    }
                });
    }

    private String createAsyncRequestEndpoint() {
        return addZoneAndCustomer(
                new UrlQueryBuilder(
                        configuration.getAsynchronousRequestEndpoint()))
                .build();
    }

    private String createAsyncResultEndpoint(String responseId) {
        UrlQueryBuilder endpoint = addZoneAndCustomer(
                new UrlQueryBuilder(
                        configuration.getAsynchronousResultEndpoint()));
        endpoint.add("response_id", responseId);
        return endpoint.build();
    }

    private UrlQueryBuilder addZoneAndCustomer(
            UrlQueryBuilder endpoint) {

        return endpoint
                .add("zone", configuration.getZone())
                .add("customer", configuration.getCustomer());
    }

    private boolean isPending(String response) {
        if (response == null) {
            return true;
        }
        String normalized = response.trim();
        return normalized.isEmpty()
                || "Request is pending".equalsIgnoreCase(
                        trimQuotes(normalized));
    }

    private String trimQuotes(String value) {
        if (value.length() >= 2
                && value.startsWith("\"")
                && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private SearchEngine configuredEngine() {
        return configuration.getSearchEngine()
                .getSearchEngine();
    }

    @Override
    public void close() {
        pollingExecutor.shutdownNow();
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

package com.aresstack.askai.research.search.http;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class AsyncJsonHttpClient
        implements AutoCloseable {

    private final AsyncHttpClient httpClient;
    private final RequestAuthentication authentication;
    private final int requestTimeoutMillis;

    public AsyncJsonHttpClient(
            AsyncHttpClient httpClient,
            RequestAuthentication authentication,
            int requestTimeoutMillis) {

        if (httpClient == null) {
            throw new IllegalArgumentException(
                    "httpClient must not be null");
        }
        if (authentication == null) {
            throw new IllegalArgumentException(
                    "authentication must not be null");
        }
        if (requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutMillis must be positive");
        }

        this.httpClient = httpClient;
        this.authentication = authentication;
        this.requestTimeoutMillis =
                requestTimeoutMillis;
    }

    public CompletableFuture<String> get(String url) {
        return get(url, Collections.<String, String>emptyMap());
    }

    public CompletableFuture<String> get(
            String url,
            Map<String, String> headers) {

        BoundRequestBuilder request =
                httpClient.prepareGet(url);

        applyCommonSettings(request, headers);
        return execute(request);
    }

    public CompletableFuture<String> postJson(
            String url,
            String requestBody) {

        return postJson(
                url,
                requestBody,
                Collections.<String, String>emptyMap());
    }

    public CompletableFuture<String> postJson(
            String url,
            String requestBody,
            Map<String, String> headers) {

        if (requestBody == null) {
            throw new IllegalArgumentException(
                    "requestBody must not be null");
        }

        BoundRequestBuilder request =
                httpClient.preparePost(url)
                        .setHeader(
                                "Content-Type",
                                "application/json; charset=UTF-8")
                        .setBody(requestBody);

        applyCommonSettings(request, headers);
        return execute(request);
    }

    private void applyCommonSettings(
            BoundRequestBuilder request,
            Map<String, String> headers) {

        request.setHeader("Accept", "application/json");
        request.setRequestTimeout(requestTimeoutMillis);

        if (headers != null) {
            for (Map.Entry<String, String> entry
                    : headers.entrySet()) {

                if (entry.getKey() != null
                        && entry.getValue() != null) {

                    request.setHeader(
                            entry.getKey(),
                            entry.getValue());
                }
            }
        }

        authentication.apply(request);
    }

    private CompletableFuture<String> execute(
            BoundRequestBuilder request) {

        CompletableFuture<Response> responseFuture =
                request.execute().toCompletableFuture();

        return responseFuture.thenApply(
                new Function<Response, String>() {
                    @Override
                    public String apply(Response response) {
                        return extractBody(response);
                    }
                });
    }

    private String extractBody(Response response) {
        int statusCode = response.getStatusCode();
        String body = response.getResponseBody();

        if (statusCode >= 200 && statusCode < 300) {
            return body == null ? "" : body;
        }

        throw new HttpResponseException(
                statusCode,
                body);
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not close AsyncHttpClient",
                    exception);
        }
    }
}

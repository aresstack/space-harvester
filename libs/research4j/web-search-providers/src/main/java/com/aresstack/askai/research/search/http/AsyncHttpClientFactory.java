package com.aresstack.askai.research.search.http;

import com.aresstack.askai.research.search.config.ConfigurationValidation;
import com.aresstack.askai.research.search.config.HttpTransportConfiguration;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;

public final class AsyncHttpClientFactory {

    public AsyncHttpClient create(
            HttpTransportConfiguration configuration) {

        ConfigurationValidation.validateTransport(
                configuration);

        DefaultAsyncHttpClientConfig clientConfiguration =
                new DefaultAsyncHttpClientConfig.Builder()
                        .setConnectTimeout(
                                configuration
                                        .getConnectTimeoutMillis())
                        .setReadTimeout(
                                configuration
                                        .getReadTimeoutMillis())
                        .setRequestTimeout(
                                configuration
                                        .getRequestTimeoutMillis())
                        .setMaxConnections(
                                configuration
                                        .getMaxConnections())
                        .setMaxConnectionsPerHost(
                                configuration
                                        .getMaxConnectionsPerHost())
                        .setFollowRedirect(
                                configuration
                                        .isFollowRedirects())
                        .setKeepAlive(
                                configuration.isKeepAlive())
                        .setCompressionEnforced(
                                configuration
                                        .isCompressionEnabled())
                        .setUserAgent(
                                configuration.getUserAgent())
                        .build();

        return new DefaultAsyncHttpClient(
                clientConfiguration);
    }
}

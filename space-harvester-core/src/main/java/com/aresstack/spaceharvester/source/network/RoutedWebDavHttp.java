package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.source.confluence.WebClientConfluenceTransport;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reuse Confluence's HTTP transport for WebDAV-specific methods.
 * Keep proxy routing, proxy login, TLS and pooling in a single implementation.
 */
public final class RoutedWebDavHttp implements AutoCloseable {

    private final RoutedHttpPort transport;
    private final int maximumResponseBytes;
    private final Duration requestTimeout;

    public RoutedWebDavHttp(HttpRoutePort routes, ProxyCredentials proxyCredentials,
                            X509TrustManager trustManager, int maximumResponseBytes) {
        this(routes, proxyCredentials, trustManager, maximumResponseBytes, SourceTimeouts.DEFAULT_WEBDAV_REQUEST);
    }

    /** Mit eigener Frist je Anfrage ({@code network.timeouts.webdav-request}). */
    public RoutedWebDavHttp(HttpRoutePort routes, ProxyCredentials proxyCredentials,
                            X509TrustManager trustManager, int maximumResponseBytes, Duration requestTimeout) {
        this.requestTimeout = java.util.Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maximumResponseBytes < 1) {
            throw new IllegalArgumentException("maximumResponseBytes must be positive");
        }
        this.maximumResponseBytes = maximumResponseBytes;
        SSLContext tls = tls(trustManager);
        this.transport = WebClientConfluenceTransport.builder()
                .routes(routes)
                .proxyCredentials(proxyCredentials)
                .clientTls(tls)
                .anonymousTls(tls)
                .build();
    }

    public Result exchange(URI uri, String method, Map<String, String> headers) {
        return exchange(uri, method, headers, maximumResponseBytes);
    }

    /** Bound each media-range response independently of the normal document-size allowance. */
    public Result exchange(URI uri, String method, Map<String, String> headers, int responseLimit) {
        if (responseLimit < 1 || responseLimit > maximumResponseBytes) {
            throw new IllegalArgumentException("Invalid WebDAV response limit");
        }
        try {
            RoutedHttpPort.Response response = transport.requestOnceAsync(
                    method, uri, headers, responseLimit, requestTimeout)
                    .toCompletableFuture().join();
            return new Result(response.status(), response.body(), response.location());
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof IOException && cause.getMessage() != null
                    && cause.getMessage().contains("Proxy-Route")
                    && cause.getMessage().contains("nicht")) {
                throw new RouteUnavailableException("BSCW proxy route unavailable", cause);
            }
            throw new IllegalStateException("BSCW WebDAV transport failed", cause);
        }
    }

    @Override
    public void close() {
        transport.close();
    }

    private static SSLContext tls(X509TrustManager trustManager) {
        try {
            if (trustManager == null) {
                return SSLContext.getDefault();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustManager}, null);
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialize WebDAV TLS context", exception);
        }
    }

    public record Result(int status, byte[] body, String location) {
        public Result(int status, byte[] body) {
            this(status, body, null);
        }
    }

    public static final class RouteUnavailableException extends RuntimeException {
        public RouteUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

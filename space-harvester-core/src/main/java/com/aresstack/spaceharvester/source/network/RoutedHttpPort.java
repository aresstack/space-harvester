package com.aresstack.spaceharvester.source.network;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Send one HTTP request through the configured route, proxy and TLS context.
 * Preserve the original response bytes without interpreting the source protocol.
 */
public interface RoutedHttpPort extends AutoCloseable {

    CompletionStage<Response> requestOnceAsync(String method, URI uri, Map<String, String> headers,
                                               int maxBytes, Duration timeout);

    /**
     * Wie oben, mit einem Körper (etwa einem Formular); {@code body == null} heißt ohne Körper. Ohne Unterstützung
     * scheitert die Stufe.
     */
    default CompletionStage<Response> requestOnceAsync(String method, URI uri, Map<String, String> headers,
                                                       byte[] body, int maxBytes, Duration timeout) {
        if (body == null) {
            return requestOnceAsync(method, uri, headers, maxBytes, timeout);
        }
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("Transport kann keinen Körper senden"));
    }

    @Override
    void close();

    record Response(int status, byte[] body, String location) {
        public Response(int status, byte[] body) {
            this(status, body, null);
        }
    }
}

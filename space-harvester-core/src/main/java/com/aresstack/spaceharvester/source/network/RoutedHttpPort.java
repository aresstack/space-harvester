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

    @Override
    void close();

    record Response(int status, byte[] body) {
    }
}

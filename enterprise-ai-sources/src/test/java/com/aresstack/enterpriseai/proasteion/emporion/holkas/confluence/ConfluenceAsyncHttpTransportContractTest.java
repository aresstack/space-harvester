package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.junit.AfterClass;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prüft den Vertragstest selbst mit einem reinen Test-Transport, der den synchronen Transport auf einem eigenen
 * Thread-Pool ausführt. Das ist kein Vorbild für produktive Implementierungen (die nutzen nicht blockierende I/O),
 * zeigt aber, dass der Vertrag zum synchronen Verhalten passt.
 */
public class ConfluenceAsyncHttpTransportContractTest extends ConfluenceAsyncHttpTransportContract {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "contract-test-transport");
        thread.setDaemon(true);
        return thread;
    });

    @AfterClass
    public static void stopPool() {
        POOL.shutdownNow();
    }

    @Override
    protected ConfluenceAsyncHttpTransport transport() {
        final UrlConnectionConfluenceTransport sync =
                UrlConnectionConfluenceTransport.builder().proxy(Proxy.NO_PROXY).readTimeoutMillis(5000).build();
        return new ConfluenceAsyncHttpTransport() {
            @Override
            public CompletionStage<ConfluenceHttpResponse> getAsync(URI uri, Map<String, String> headers,
                                                                    int maxBytes) {
                return onPool(() -> sync.get(uri, headers, maxBytes));
            }

            @Override
            public CompletionStage<ConfluenceHttpResponse> getAnonymousAsync(URI uri, Map<String, String> headers,
                                                                             int maxBytes) {
                return onPool(() -> sync.getAnonymous(uri, headers, maxBytes));
            }
        };
    }

    private interface Call {
        ConfluenceHttpResponse run() throws IOException;
    }

    private static CompletionStage<ConfluenceHttpResponse> onPool(Call call) {
        CompletableFuture<ConfluenceHttpResponse> result = new CompletableFuture<ConfluenceHttpResponse>();
        POOL.execute(() -> {
            try {
                result.complete(call.run());
            } catch (IOException | RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}

package com.aresstack.askai.research.runtime.search.provider.async;

/**
 * Thrown when the async search registry is used after {@link AsyncSearchProviderRegistry#close()} — a new
 * search or a {@code reload()} against a closed registry. A typed lifecycle error so callers never trigger
 * a use-after-close on the underlying AsyncHttpClients.
 */
public final class AsyncSearchRegistryClosedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public AsyncSearchRegistryClosedException() {
        super("The async web search registry is closed");
    }
}

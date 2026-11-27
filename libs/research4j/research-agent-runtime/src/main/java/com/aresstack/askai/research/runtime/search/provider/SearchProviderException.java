package com.aresstack.askai.research.runtime.search.provider;

/**
 * The base of every typed provider failure. It carries the {@link SearchProviderId} that failed and whether
 * the failure is retryable/fallback-eligible — so the strategy layer can route (a FallbackSearchStrategy
 * may try the next provider on a retryable failure) without parsing free-text messages. A selected provider
 * is never silently swapped: only an explicitly configured fallback strategy may act on this.
 */
public abstract class SearchProviderException extends RuntimeException {

    private final SearchProviderId providerId;
    private final boolean retryable;

    protected SearchProviderException(SearchProviderId providerId, boolean retryable, String message) {
        super(message);
        this.providerId = providerId;
        this.retryable = retryable;
    }

    protected SearchProviderException(SearchProviderId providerId, boolean retryable, String message,
                                      Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.retryable = retryable;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    /** @return {@code true} when an explicit fallback strategy may try another provider instead. */
    public boolean isRetryable() {
        return retryable;
    }
}

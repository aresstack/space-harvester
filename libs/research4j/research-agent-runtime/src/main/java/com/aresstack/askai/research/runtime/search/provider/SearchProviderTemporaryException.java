package com.aresstack.askai.research.runtime.search.provider;

/**
 * A temporary/technical provider failure (5xx, transport). Retryable: an optional retry or a configured
 * FallbackSearchStrategy may try the next provider.
 */
public final class SearchProviderTemporaryException extends SearchProviderException {

    public SearchProviderTemporaryException(SearchProviderId providerId, String message) {
        super(providerId, true, message);
    }

    public SearchProviderTemporaryException(SearchProviderId providerId, String message, Throwable cause) {
        super(providerId, true, message, cause);
    }
}

package com.aresstack.askai.research.runtime.search.provider;

/**
 * The provider reported a rate limit. Retryable: a configured FallbackSearchStrategy may try the next
 * provider (a SingleProviderSearchStrategy still fails visibly rather than falling back).
 */
public final class SearchProviderRateLimitException extends SearchProviderException {

    public SearchProviderRateLimitException(SearchProviderId providerId, String message) {
        super(providerId, true, message);
    }
}

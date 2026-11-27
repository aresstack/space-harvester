package com.aresstack.askai.research.runtime.search.provider;

/**
 * The configured {@link SearchEngine} is not supported by this provider. NOT retryable: the configuration
 * is factually invalid and must be corrected.
 */
public final class SearchProviderUnsupportedEngineException extends SearchProviderException {

    public SearchProviderUnsupportedEngineException(SearchProviderId providerId, SearchEngine engine) {
        super(providerId, false, "Provider " + providerId + " does not support engine " + engine);
    }
}

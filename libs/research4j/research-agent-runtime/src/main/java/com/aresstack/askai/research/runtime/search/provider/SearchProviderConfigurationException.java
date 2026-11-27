package com.aresstack.askai.research.runtime.search.provider;

/**
 * The provider is missing or has invalid configuration (e.g. no API credentials, no location). NOT
 * retryable: the user must fix the configuration; never a silent fallback.
 */
public final class SearchProviderConfigurationException extends SearchProviderException {

    public SearchProviderConfigurationException(SearchProviderId providerId, String message) {
        super(providerId, false, message);
    }
}

package com.aresstack.askai.research.runtime.search.provider;

/**
 * The provider returned an invalid or uninterpretable response (not JSON, missing required fields, an
 * error status the adapter cannot map to a more specific type). NOT retryable by default: a malformed
 * answer will not become valid on retry.
 */
public final class SearchProviderResponseException extends SearchProviderException {

    public SearchProviderResponseException(SearchProviderId providerId, String message) {
        super(providerId, false, message);
    }
}

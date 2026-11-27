package com.aresstack.askai.research.runtime.search.provider;

/**
 * The provider rejected the credentials. NOT retryable: the user must correct the credentials; the failure
 * is surfaced visibly and never masked by falling back to another provider.
 */
public final class SearchProviderAuthenticationException extends SearchProviderException {

    public SearchProviderAuthenticationException(SearchProviderId providerId, String message) {
        super(providerId, false, message);
    }
}

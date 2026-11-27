package com.aresstack.askai.research.runtime.search.provider;

/**
 * Thrown by a catalogued-but-unimplemented provider. NOT retryable: it is a configuration error (the
 * provider must not have been selected), never a reason to fall back to another provider silently.
 */
public final class SearchProviderNotImplementedException extends SearchProviderException {

    public SearchProviderNotImplementedException(SearchProviderId providerId) {
        super(providerId, false, "Search provider is not implemented: " + providerId);
    }
}

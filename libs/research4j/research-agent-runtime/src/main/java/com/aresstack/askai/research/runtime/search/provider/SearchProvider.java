package com.aresstack.askai.research.runtime.search.provider;

/**
 * A single external search API adapter. It owns authentication, endpoints, provider parameters,
 * request/response serialization, error codes, rate limits, pagination and provider-specific engine
 * options. It does NOT decide whether other providers are called, when enough results were found, whether a
 * fallback runs, or how multiple providers' results are merged — those are the {@link
 * com.aresstack.askai.research.runtime.search.SearchStrategy}'s job.
 */
public interface SearchProvider {

    SearchProviderId getProviderId();

    /**
     * Execute one search. Returns organic hits (possibly empty). Every failure is a typed
     * {@link SearchProviderException}; a selected provider is never silently replaced by another.
     */
    SearchProviderResult search(SearchProviderRequest request);

    SearchProviderAvailability getAvailability();
}

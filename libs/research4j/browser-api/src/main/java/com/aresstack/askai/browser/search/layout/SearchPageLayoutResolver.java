package com.aresstack.askai.browser.search.layout;

/**
 * Resolves the layout of a mechanically ununderstood SERP snapshot. The single production
 * implementation drives a {@link com.aresstack.askai.browser.search.inference.StructuredInferencePort}
 * but knows no concrete model library; the sidecar stays model-free by simply never being handed a
 * resolver. The contract is total: it never throws for an ununderstood page — it returns a typed
 * {@link SearchPageLayoutResolverResult} the caller routes on.
 */
public interface SearchPageLayoutResolver {

    SearchPageLayoutResolverResult resolve(SearchPageLayoutResolutionRequest request);
}

package com.aresstack.askai.research.runtime.search.provider;

/**
 * The runtime availability of an ACTUAL, implemented provider instance (independent of any single request
 * outcome). There is deliberately no {@code NOT_IMPLEMENTED} value here: unimplemented providers have no
 * instance at all — the registry throws {@link SearchProviderNotImplementedException} instead of returning
 * one. Whether a catalogued id is implemented is expressed by {@link SearchProviderImplementationStatus}.
 */
public enum SearchProviderAvailability {
    AVAILABLE,
    NOT_CONFIGURED
}

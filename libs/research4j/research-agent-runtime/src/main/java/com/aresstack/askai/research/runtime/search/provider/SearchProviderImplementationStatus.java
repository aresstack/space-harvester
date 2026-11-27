package com.aresstack.askai.research.runtime.search.provider;

/**
 * Whether a catalogued provider has a productive implementation bound in the registry. NOT_IMPLEMENTED
 * providers exist only as an id, a descriptor and a provider-specific interface — there is no concrete
 * provider class and no object is ever created for them.
 */
public enum SearchProviderImplementationStatus {
    IMPLEMENTED,
    NOT_IMPLEMENTED
}

package com.aresstack.askai.browser.search.engine;

/**
 * How far AskAI can actually search with a catalog engine. Every engine of {@link SearchEngineCatalog} keeps its
 * entry whatever its status: an engine never disappears from the catalog because its adapter is missing or its
 * service is down; it is only never offered as working.
 */
public enum SearchEngineImplementationStatus {

    /** Implemented and covered by automated tests (fixtures); offered for searching. */
    SUPPORTED,

    /** Implemented, but not yet reliably validated against the live engine; offered, marked as experimental. */
    EXPERIMENTAL,

    /** In the catalog, but AskAI cannot search with it yet. Visible, never offered as working. */
    NOT_IMPLEMENTED,

    /** Not usable at the moment, for a documented reason ({@link SearchEngineCatalogEntry#getStatusNote()}). */
    UNAVAILABLE;

    /** Whether AskAI can search with an engine in this state. */
    public boolean isSearchable() {
        return this == SUPPORTED || this == EXPERIMENTAL;
    }
}

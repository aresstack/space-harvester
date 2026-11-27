package com.aresstack.askai.browser.search;

/**
 * Engine order, attempts and navigation limits for the legacy browser search. The forbidden flat-anchor
 * fallback is a hard invariant, NOT a setting — no field here (or anywhere) can re-enable it.
 */
public final class LegacySearchNavigationSettings {

    /**
     * The enabled engines in the user's order, and how they are worked through. There is no primary
     * engine and no fallback list: an engine is either in the run or it is not, and where it stands is
     * the user's decision.
     */
    public final com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection engineSelection;
    /** Upper bound on engine ENDPOINTS opened per search (an engine may own more than one). */
    public final int maximumEngineAttempts;
    /** Timeout for a navigation to commit (Playwright default navigation timeout). */
    public final int navigationCommitTimeoutMillis;
    /** Statically resolve engine redirect wrappers (Bing /ck/, Google /url, DDG /l/) before domain checks. */
    public final boolean redirectResolutionEnabled;
    /** URLs longer than this are never fed to the redirect resolver (defense against pathological inputs). */
    public final int maximumRedirectUrlLength;
    /** Maximum organic results returned per search. */
    public final int searchResultLimit;
    /**
     * Usable results (distinct, no search-result or shop page) after which the web search asks no further priority
     * group of Search → Engines; fewer, and the next group is searched.
     */
    public final int minimumUsableResults;
    /** Preferred content language (BCP-47, e.g. "de"); empty = engine default. */
    public final String language;
    /** Preferred region/country code (e.g. "DE"); empty = engine default. */
    public final String country;

    public LegacySearchNavigationSettings(
            com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection engineSelection,
            int maximumEngineAttempts, int navigationCommitTimeoutMillis,
            boolean redirectResolutionEnabled, int maximumRedirectUrlLength,
            int searchResultLimit, String language, String country) {
        this(engineSelection, maximumEngineAttempts, navigationCommitTimeoutMillis, redirectResolutionEnabled,
                maximumRedirectUrlLength, searchResultLimit, LegacyBrowserSearchDefaults.MINIMUM_USABLE_RESULTS,
                language, country);
    }

    public LegacySearchNavigationSettings(
            com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection engineSelection,
            int maximumEngineAttempts, int navigationCommitTimeoutMillis,
            boolean redirectResolutionEnabled, int maximumRedirectUrlLength,
            int searchResultLimit, int minimumUsableResults, String language, String country) {
        this.minimumUsableResults = minimumUsableResults;
        this.engineSelection = engineSelection;
        this.maximumEngineAttempts = maximumEngineAttempts;
        this.navigationCommitTimeoutMillis = navigationCommitTimeoutMillis;
        this.redirectResolutionEnabled = redirectResolutionEnabled;
        this.maximumRedirectUrlLength = maximumRedirectUrlLength;
        this.searchResultLimit = searchResultLimit;
        this.language = language;
        this.country = country;
    }
}

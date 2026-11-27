package com.aresstack.askai.browser.search;

/**
 * The honest outcome of one mechanical SERP analysis. An UNUNDERSTOOD layout is an
 * {@link #EXTRACTION_FAILED}, NEVER a "search engine without hits" — the two states drive the same
 * engine-fallback policy but mean different things to diagnostics and to the later AI repair.
 */
public enum SearchPageAnalysisOutcome {

    /** Validated result blocks were extracted. */
    ORGANIC_RESULTS,
    /** A valid SERP structure or an explicit no-results indication — genuinely zero organic hits. */
    NO_ORGANIC_RESULTS,
    /** No plausible result region, contradictory structure, capture failure or low confidence. */
    EXTRACTION_FAILED
}

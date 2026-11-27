package com.aresstack.askai.browser.search.analysis;

/**
 * Coarse region type of a SERP container. Non-content is NOT automatically a menu: a knowledge
 * panel, an ad module or a "people also ask" block is {@link #AUXILIARY_CONTENT} or
 * {@link #ADVERTISEMENT}, never lumped into {@link #NAVIGATION}.
 */
public enum SearchPageRegionClassification {
    ORGANIC_RESULTS,
    NAVIGATION,
    SEARCH_CONTROLS,
    FILTERS,
    AUXILIARY_CONTENT,
    ADVERTISEMENT,
    PAGINATION,
    FOOTER,
    UNKNOWN
}

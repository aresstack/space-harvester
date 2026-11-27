package com.aresstack.askai.browser.search.repair;

/**
 * The workflow-level status of a {@code web_search_prepare} call — distinct from the per-page
 * {@code SearchPageAnalysisOutcome}. {@link #REPAIR_REQUIRED} means one or more low-confidence pages
 * were captured whose layout the model-free sidecar could not resolve; the research runtime must
 * attempt repair before any engine is written off. It must NOT be treated as a finished
 * {@code EXTRACTION_FAILED} and must not be silently skipped to the next engine.
 */
public enum WebSearchPreparationStatus {
    ORGANIC_RESULTS,
    NO_ORGANIC_RESULTS,
    REPAIR_REQUIRED,
    CHALLENGE_PENDING,
    FAILED
}

package com.aresstack.askai.browser.search.layout;

/**
 * The typed outcome of an AI layout-resolution attempt. Only {@link #RESOLVED} yields a decision the
 * extractor may apply; every other value routes back to the existing engine-fallback policy without
 * inventing results.
 */
public enum SearchPageLayoutResolverOutcome {
    /** A model decision was produced, parsed and (from A4c on) validated against the artifact. */
    RESOLVED,
    /** The AI resolver is disabled by settings — the model was never called. */
    AI_DISABLED,
    /** No model adapter was available (or it failed/timed out) — no usable decision. */
    AI_UNAVAILABLE,
    /** Every attempt failed parsing or validation within the retry budget. */
    VALIDATION_FAILED,
    /** The cancellation signal fired before a usable decision was produced. */
    CANCELLED
}

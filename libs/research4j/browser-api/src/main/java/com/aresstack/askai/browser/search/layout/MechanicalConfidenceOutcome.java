package com.aresstack.askai.browser.search.layout;

/**
 * The mechanical analyzer's confidence verdict for one SERP snapshot, projected into the neutral
 * analysis artifact. {@link #HIGH_CONFIDENCE} means the existing A3 extraction owns the page and the
 * AI layout resolver must NOT be called; {@link #LOW_CONFIDENCE} is the only outcome that admits an
 * AI repair attempt.
 */
public enum MechanicalConfidenceOutcome {
    HIGH_CONFIDENCE,
    LOW_CONFIDENCE
}

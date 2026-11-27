package com.aresstack.askai.browser.search.analysis;

/**
 * The independent signal families of the mechanical analysis. A family only counts as
 * DISCRIMINATING when it emitted at least one nonzero signal — if fewer than the configured
 * minimum of families discriminate, the whole analysis is LOW_CONFIDENCE.
 */
public enum SignalFamily {
    DOM_SEMANTICS,
    GEOMETRY,
    VISUAL_STYLING,
    LINK_STRUCTURE,
    TEXT_STRUCTURE,
    REPEATED_STRUCTURE
}

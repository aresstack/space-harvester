package com.aresstack.knowledge4j.source;

/**
 * How much protection content needs, set by the host. Ordered from least to most protected; combining content
 * keeps the highest level. Content without a known classification counts as {@link #INTERNAL}.
 */
public enum Classification {
    /** Released for external services by the host. */
    PUBLIC,
    /** Must stay inside the organisation; the default for everything the host has not released. */
    INTERNAL,
    /** Must stay inside a narrower circle than the organisation (the host defines which). */
    RESTRICTED;

    public Classification max(Classification other) {
        return other == null || other.ordinal() <= ordinal() ? this : other;
    }

    public boolean atLeast(Classification other) {
        return ordinal() >= other.ordinal();
    }
}

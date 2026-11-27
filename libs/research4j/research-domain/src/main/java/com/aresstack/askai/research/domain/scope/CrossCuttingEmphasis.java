package com.aresstack.askai.research.domain.scope;

/**
 * A weighting that runs ACROSS the facets rather than belonging to one of them — "regulation matters
 * everywhere", "always look at it from the user's perspective". Kept separate from {@link CoverageEmphasis}
 * precisely because it must not be attached to a single facet: a cross-cutting dimension applies to the
 * whole investigation area.
 */
public final class CrossCuttingEmphasis {

    private final String dimension;
    private final CoverageEmphasis.Importance importance;

    public CrossCuttingEmphasis(String dimension, CoverageEmphasis.Importance importance) {
        if (dimension == null || dimension.trim().isEmpty()) {
            throw new IllegalArgumentException("dimension must not be empty");
        }
        this.dimension = dimension.trim();
        this.importance = importance == null ? CoverageEmphasis.Importance.MEDIUM : importance;
    }

    /** The dimension in the user's words (e.g. "regulatorische Einordnung", "Messgenauigkeit"). */
    public String getDimension() {
        return dimension;
    }

    public CoverageEmphasis.Importance getImportance() {
        return importance;
    }
}

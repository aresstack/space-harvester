package com.aresstack.askai.research.domain.scope;

/**
 * How much weight ONE facet carries — deliberately in THREE independent dimensions, because collapsing them
 * loses exactly the distinctions a user makes:
 * <ul>
 * <li>{@link Importance}: how much it matters for the mission,</li>
 * <li>{@link ResearchDepth}: how thoroughly it must be researched — "important" does NOT automatically mean
 *     "research it exhaustively", and a topic can need deep digging precisely because it is unclear,</li>
 * <li>{@link #getOutputShareHint()}: a HINT for a later document, never a chapter weighting. Phase 1 records
 *     what the user said; it does not pre-empt any outline.</li>
 * </ul>
 */
public final class CoverageEmphasis {

    /** How much this facet matters for the mission. */
    public enum Importance { LOW, MEDIUM, HIGH }

    /** How thoroughly it must be researched — independent of how important it is. */
    public enum ResearchDepth { OVERVIEW, STANDARD, DEEP, EXHAUSTIVE }

    /** No hint given. */
    public static final int NO_SHARE_HINT = -1;

    private final String targetFacetId;
    private final Importance importance;
    private final ResearchDepth researchDepth;
    private final int outputShareHint;

    public CoverageEmphasis(String targetFacetId, Importance importance, ResearchDepth researchDepth) {
        this(targetFacetId, importance, researchDepth, NO_SHARE_HINT);
    }

    public CoverageEmphasis(String targetFacetId, Importance importance, ResearchDepth researchDepth,
                            int outputShareHint) {
        if (targetFacetId == null || targetFacetId.trim().isEmpty()) {
            throw new IllegalArgumentException("targetFacetId must not be empty");
        }
        this.targetFacetId = targetFacetId.trim();
        this.importance = importance == null ? Importance.MEDIUM : importance;
        this.researchDepth = researchDepth == null ? ResearchDepth.STANDARD : researchDepth;
        this.outputShareHint = outputShareHint < 0 || outputShareHint > 100
                ? NO_SHARE_HINT : outputShareHint;
    }

    /** The {@link ScopeFacet#getFacetId()} this emphasis refers to. */
    public String getTargetFacetId() {
        return targetFacetId;
    }

    public Importance getImportance() {
        return importance;
    }

    public ResearchDepth getResearchDepth() {
        return researchDepth;
    }

    /** A rough share of the later result in percent, or {@link #NO_SHARE_HINT} — advisory only. */
    public int getOutputShareHint() {
        return outputShareHint;
    }

    public boolean hasShareHint() {
        return outputShareHint != NO_SHARE_HINT;
    }
}

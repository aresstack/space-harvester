package com.aresstack.askai.research.domain.scope;

/**
 * The RAW measurement of one probe in a sweep — two orthogonal axes, exactly as agreed:
 * <pre>
 *   mission relevance:   is this plausibly part of the coarse mission at all?
 *   fence relationship:  is it already represented by the negotiated posts?
 * </pre>
 * "Far from the fence" alone is mathematically identical to "completely off-topic" — only the
 * COMBINATION (relevant to the mission AND badly explained by the fence) marks a plausible missing
 * post. The category is an advisory bucket, never a decision; there is deliberately NO coverage
 * percentage anywhere.
 */
public final class ProbeReading {

    /**
     * Which post TYPE relates to this probe — one of the two orthogonal fence dimensions.
     * AMBIGUOUS = a genuine IN/OUT conflict (margin within the caller's boundary margin);
     * NONE = the fence has no posts at all.
     */
    public enum FenceRelation {
        IN,
        OUT,
        PROVISIONAL,
        AMBIGUOUS,
        NONE
    }

    /**
     * How well the fence explains this probe — the second orthogonal dimension.
     * UNEXPLAINED = below the calibrated known-region floor (no sufficient relation to ANY post);
     * SWEEP_NOVEL = sufficiently known, but unusually weakly explained relative to THIS sweep;
     * WELL_EXPLAINED = the normal case. {@code FenceRelation=IN + NoveltyRelation=SWEEP_NOVEL} is
     * NOT a contradiction: it reads "belongs to a known region, but stretches its edge".
     */
    public enum NoveltyRelation {
        WELL_EXPLAINED,
        SWEEP_NOVEL,
        UNEXPLAINED
    }

    /** Advisory buckets DERIVED from the orthogonal dimensions. IRRELEVANT never reaches the agent. */
    public enum Category {
        /** Mission-relevant and clearly near an existing IN post — usually uninteresting. */
        KNOWN,
        /** Mission-relevant but clearly near a negotiated OUT post — drift detection material. */
        EXCLUDED,
        /**
         * Mission-relevant and best explained by a PROVISIONAL post: this region was already
         * raised but the user has not decided it yet — the question is "klären?", never
         * "unentdeckt". A probe on a provisional post is by definition NOT unexplored.
         */
        PENDING,
        /** Mission-relevant and similarly plausible on both sides — ideal for ONE user question. */
        BOUNDARY,
        /**
         * Mission-relevant, SUFFICIENTLY known (a real relation to an existing post exists), yet
         * sweep-relatively unusually weakly explained: a possible EXTENSION of a known region's
         * edge — "das Thema hatten wir, aber der Zaun ist dort womöglich zu grob". A different
         * question than UNEXPLORED.
         */
        EXTENSION,
        /**
         * Mission-relevant and NOT sufficiently explained by ANY post (below the calibrated
         * known-region floor) — a plausible, genuinely missing island. This is the ONLY meaning of
         * "unexplored": a probe with a sufficient relation to a known post never carries it.
         */
        UNEXPLORED,
        /** Not plausibly part of the mission — however novel, never an interesting hole. */
        IRRELEVANT
    }

    private final ScopeProbe probe;
    private final double missionRelevance;
    private final ScopeFenceEvaluator.Reading fenceReading;
    private final double knownSimilarity;
    private final Category category;
    private final int sweepNoveltyRank;
    private final FenceRelation fenceRelation;
    private final NoveltyRelation noveltyRelation;

    public ProbeReading(ScopeProbe probe, double missionRelevance,
                        ScopeFenceEvaluator.Reading fenceReading, Category category,
                        int sweepNoveltyRank, FenceRelation fenceRelation,
                        NoveltyRelation noveltyRelation) {
        if (probe == null) {
            throw new IllegalArgumentException("probe must not be null");
        }
        if (fenceReading == null) {
            throw new IllegalArgumentException("fenceReading must not be null");
        }
        this.probe = probe;
        this.missionRelevance = missionRelevance;
        this.fenceReading = fenceReading;
        this.knownSimilarity = Math.max(fenceReading.nearestInSimilarity,
                Math.max(fenceReading.nearestOutSimilarity,
                        fenceReading.nearestProvisionalSimilarity));
        this.category = category == null ? Category.IRRELEVANT : category;
        this.sweepNoveltyRank = Math.max(0, sweepNoveltyRank);
        this.fenceRelation = fenceRelation == null ? FenceRelation.NONE : fenceRelation;
        this.noveltyRelation = noveltyRelation == null
                ? NoveltyRelation.UNEXPLAINED : noveltyRelation;
    }

    public ScopeProbe getProbe() {
        return probe;
    }

    public double getMissionRelevance() {
        return missionRelevance;
    }

    public ScopeFenceEvaluator.Reading getFenceReading() {
        return fenceReading;
    }

    /** {@code max cos} to ANY post (IN, OUT or PROVISIONAL) — how well the fence explains this probe. */
    public double getKnownSimilarity() {
        return knownSimilarity;
    }

    /**
     * {@code 1 - knownSimilarity}: novelty RELATIVE TO THE FENCE — never truth, never "missing scope"
     * by itself, and never comparable across embedding models without calibration.
     */
    public double getNovelty() {
        return 1.0d - knownSimilarity;
    }

    public Category getCategory() {
        return category;
    }

    /**
     * The SWEEP-RELATIVE novelty position among the mission-relevant probes of this sweep:
     * 1 = least explained by the fence, ascending; 0 = not ranked (irrelevant probes). Kept so a
     * selection stays explainable after the fact — which probes were unusually unexplained HERE.
     */
    public int getSweepNoveltyRank() {
        return sweepNoveltyRank;
    }

    /** Which post type relates to this probe (orthogonal dimension one). */
    public FenceRelation getFenceRelation() {
        return fenceRelation;
    }

    /** How well the fence explains this probe (orthogonal dimension two). */
    public NoveltyRelation getNoveltyRelation() {
        return noveltyRelation;
    }

    /** The Z2 geometry hint — DIAGNOSTIC only; Z3's category never simply copies it. */
    public ScopeFenceEvaluator.Hint getLocalFenceHint() {
        return fenceReading.hint;
    }

    /**
     * Which post TYPE explains this probe best — a RAW orthogonal observation ({@code null} when
     * the fence has no posts at all). It says nothing about HOW WELL that side explains the probe;
     * the categories are advisory derivations over these dimensions, never the primary data.
     */
    public ScopeAnchor.Membership getDominantMembership() {
        double in = fenceReading.nearestInSimilarity;
        double out = fenceReading.nearestOutSimilarity;
        double provisional = fenceReading.nearestProvisionalSimilarity;
        if (in <= 0.0d && out <= 0.0d && provisional <= 0.0d) {
            return null;
        }
        if (provisional >= in && provisional >= out) {
            return ScopeAnchor.Membership.PROVISIONAL;
        }
        return in >= out ? ScopeAnchor.Membership.IN : ScopeAnchor.Membership.OUT;
    }
}

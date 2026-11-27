package com.aresstack.askai.browser.search;

/**
 * Visual SERP analysis contract (screenshot-based container detection, future slice): geometric
 * probes, background/separation heuristics and their weights. Defined now as a code contract;
 * the fields take effect once the visual analysis stage ships.
 */
public final class SearchPageVisualAnalysisSettings {

    public final boolean enabled;
    /** Colors closer than this similarity (0..1) count as the same background. */
    public final double backgroundSimilarityThreshold;
    /** Minimum color distance (0..1) for a region to count as visually distinct. */
    public final double minimumDistinctBackgroundDistance;
    /** If one color covers more than this ratio of the page, it is THE page background. */
    public final double maximumDominantColorCoverage;
    /** Regions smaller than this ratio of the viewport are noise. */
    public final double minimumVisualRegionAreaRatio;

    // Center probe rectangle (ratios of the viewport) — where the primary result column is expected.
    public final double centerProbeXRatio;
    public final double centerProbeYRatio;
    public final double centerProbeWidthRatio;
    public final double centerProbeHeightRatio;

    // Scoring weights (non-negative).
    public final double centerIntersectionWeight;
    public final double centerDistanceWeight;
    public final double distinctBackgroundWeight;
    public final double borderSeparationWeight;
    public final double shadowSeparationWeight;
    public final double spacingSeparationWeight;
    public final double regionContinuityWeight;
    public final double fullPageContainerPenalty;
    public final double edgeRegionPenalty;

    public final int maximumVisualContainers;

    public SearchPageVisualAnalysisSettings(boolean enabled, double backgroundSimilarityThreshold,
                                            double minimumDistinctBackgroundDistance,
                                            double maximumDominantColorCoverage,
                                            double minimumVisualRegionAreaRatio, double centerProbeXRatio,
                                            double centerProbeYRatio, double centerProbeWidthRatio,
                                            double centerProbeHeightRatio, double centerIntersectionWeight,
                                            double centerDistanceWeight, double distinctBackgroundWeight,
                                            double borderSeparationWeight, double shadowSeparationWeight,
                                            double spacingSeparationWeight, double regionContinuityWeight,
                                            double fullPageContainerPenalty, double edgeRegionPenalty,
                                            int maximumVisualContainers) {
        this.enabled = enabled;
        this.backgroundSimilarityThreshold = backgroundSimilarityThreshold;
        this.minimumDistinctBackgroundDistance = minimumDistinctBackgroundDistance;
        this.maximumDominantColorCoverage = maximumDominantColorCoverage;
        this.minimumVisualRegionAreaRatio = minimumVisualRegionAreaRatio;
        this.centerProbeXRatio = centerProbeXRatio;
        this.centerProbeYRatio = centerProbeYRatio;
        this.centerProbeWidthRatio = centerProbeWidthRatio;
        this.centerProbeHeightRatio = centerProbeHeightRatio;
        this.centerIntersectionWeight = centerIntersectionWeight;
        this.centerDistanceWeight = centerDistanceWeight;
        this.distinctBackgroundWeight = distinctBackgroundWeight;
        this.borderSeparationWeight = borderSeparationWeight;
        this.shadowSeparationWeight = shadowSeparationWeight;
        this.spacingSeparationWeight = spacingSeparationWeight;
        this.regionContinuityWeight = regionContinuityWeight;
        this.fullPageContainerPenalty = fullPageContainerPenalty;
        this.edgeRegionPenalty = edgeRegionPenalty;
        this.maximumVisualContainers = maximumVisualContainers;
    }
}

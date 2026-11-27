package com.aresstack.askai.browser.search;

/**
 * Mechanical (DOM-structural) SERP analysis contract for the A3 slice: candidate result containers
 * are found by repeated-sibling structure and scored; only containers above the structural confidence
 * become extraction candidates. Defined NOW so A3 is implemented against settings from its first line.
 */
public final class SearchPageAnalysisSettings {

    /** Lower-cased page texts that mark an EXPLICIT empty SERP (NO_ORGANIC_RESULTS, not a failure). */
    public final java.util.List<String> noResultsTexts;

    public final int maximumCandidateContainers;
    public final int minimumContainerTextCharacters;
    /** Minimum characters of NON-link text — pure link lists are navigation, not results. */
    public final int minimumNonLinkTextCharacters;
    /** A result list is a repeated structure: minimum similar siblings to count as one. */
    public final int minimumRepeatedSiblingCount;
    /** Containers below this structural confidence (0..1) are never extraction candidates. */
    public final double minimumResultStructuralConfidence;
    /** Containers whose link-text density exceeds this ratio (0..1) count as navigation. */
    public final double maximumNavigationLinkDensity;

    // Scoring weights/penalties (non-negative; applied by the A3 scorer).
    public final double internalLinkWeight;
    public final double externalLinkWeight;
    public final double sameHostPenalty;
    public final double sameRegistrableDomainPenalty;
    public final double subdomainPenalty;
    public final double unknownDomainPenalty;

    // A3 scoring weights (signal families of the mechanical container/region analysis).
    public final double repeatedBlockWeight;
    public final double nonLinkTextWeight;
    public final double titleLinkWeight;
    public final double snippetPresenceWeight;
    public final double headingLinkWeight;
    public final double semanticMainWeight;
    public final double navigationRolePenalty;
    /** Similarity (0..1) two sibling structure signatures need to count as the same block shape. */
    public final double resultBlockSimilarityThreshold;
    /** At least this many signal FAMILIES must discriminate, else the analysis is LOW_CONFIDENCE. */
    public final int minimumDiscriminatingSignalFamilies;
    /** A container covering at least this ratio of the document area gets the full-page penalty. */
    public final double fullPageAreaRatio;
    /** Text beyond this length adds no further score (saturation). */
    public final int textLengthSaturationCharacters;

    // A3 capture limits (the sidecar capture is bounded by construction).
    public final int maximumContainerDomDepth;
    public final int maximumCapturedContainers;
    public final int maximumLinksPerContainer;
    public final int maximumStructureSignatureDepth;
    /**
     * Capture consistency policy: how often a capture whose DOM fingerprint changed DURING the capture is
     * retried — each retry is a FULL capture (seconds on a big SERP); 0 = capture once and flag the
     * inconsistency — and how long to settle before each retry (milliseconds).
     */
    public final int maximumCaptureRetries;
    public final int captureSettleMillis;

    /**
     * The SERP-as-JSON safety net: when the structured block extraction yields FEWER candidates than
     * this, the page's EXTERNAL links are harvested as additional candidates — each with its visible
     * text and surrounding excerpt, exactly the shape a SERP API's JSON delivers — and the mandatory
     * reranker judges them. Engines whose block structure defeats the detector (Bing collapsing to one
     * candidate) then still deliver the whole result page. 0 disables the harvest.
     */
    public final int linkHarvestMinimumStructuredCandidates;
    /** Upper bound of TOTAL candidates (structured + harvested) after the harvest. */
    public final int linkHarvestMaximumCandidates;
    /**
     * SERP-chrome filter for the harvest: links whose host ends in one of these registrable domains
     * are NEVER harvested — the engine owner's legal/consent/footer pages (Bing links microsoft.com
     * everywhere) are page furniture, not results. Empty list = no exclusion.
     */
    public final java.util.List<String> linkHarvestExcludedDomains;

    /** The pre-capture-policy signature (fixtures): the default retry policy applies. */
    public SearchPageAnalysisSettings(java.util.List<String> noResultsTexts,
                                      int maximumCandidateContainers, int minimumContainerTextCharacters,
                                      int minimumNonLinkTextCharacters, int minimumRepeatedSiblingCount,
                                      double minimumResultStructuralConfidence,
                                      double maximumNavigationLinkDensity, double internalLinkWeight,
                                      double externalLinkWeight, double sameHostPenalty,
                                      double sameRegistrableDomainPenalty, double subdomainPenalty,
                                      double unknownDomainPenalty, double repeatedBlockWeight,
                                      double nonLinkTextWeight, double titleLinkWeight,
                                      double snippetPresenceWeight, double headingLinkWeight,
                                      double semanticMainWeight, double navigationRolePenalty,
                                      double resultBlockSimilarityThreshold,
                                      int minimumDiscriminatingSignalFamilies,
                                      double fullPageAreaRatio,
                                      int textLengthSaturationCharacters, int maximumContainerDomDepth,
                                      int maximumCapturedContainers, int maximumLinksPerContainer,
                                      int maximumStructureSignatureDepth,
                                      int linkHarvestMinimumStructuredCandidates,
                                      int linkHarvestMaximumCandidates,
                                      java.util.List<String> linkHarvestExcludedDomains) {
        this(noResultsTexts, maximumCandidateContainers, minimumContainerTextCharacters,
                minimumNonLinkTextCharacters, minimumRepeatedSiblingCount, minimumResultStructuralConfidence,
                maximumNavigationLinkDensity, internalLinkWeight, externalLinkWeight, sameHostPenalty,
                sameRegistrableDomainPenalty, subdomainPenalty, unknownDomainPenalty, repeatedBlockWeight,
                nonLinkTextWeight, titleLinkWeight, snippetPresenceWeight, headingLinkWeight,
                semanticMainWeight, navigationRolePenalty, resultBlockSimilarityThreshold,
                minimumDiscriminatingSignalFamilies, fullPageAreaRatio, textLengthSaturationCharacters,
                maximumContainerDomDepth, maximumCapturedContainers, maximumLinksPerContainer,
                maximumStructureSignatureDepth, LegacyBrowserSearchDefaults.DEFAULT_CAPTURE_RETRIES,
                LegacyBrowserSearchDefaults.DEFAULT_CAPTURE_SETTLE_MILLIS,
                linkHarvestMinimumStructuredCandidates, linkHarvestMaximumCandidates,
                linkHarvestExcludedDomains);
    }

    public SearchPageAnalysisSettings(java.util.List<String> noResultsTexts,
                                      int maximumCandidateContainers, int minimumContainerTextCharacters,
                                      int minimumNonLinkTextCharacters, int minimumRepeatedSiblingCount,
                                      double minimumResultStructuralConfidence,
                                      double maximumNavigationLinkDensity, double internalLinkWeight,
                                      double externalLinkWeight, double sameHostPenalty,
                                      double sameRegistrableDomainPenalty, double subdomainPenalty,
                                      double unknownDomainPenalty, double repeatedBlockWeight,
                                      double nonLinkTextWeight, double titleLinkWeight,
                                      double snippetPresenceWeight, double headingLinkWeight,
                                      double semanticMainWeight, double navigationRolePenalty,
                                      double resultBlockSimilarityThreshold,
                                      int minimumDiscriminatingSignalFamilies,
                                      double fullPageAreaRatio,
                                      int textLengthSaturationCharacters, int maximumContainerDomDepth,
                                      int maximumCapturedContainers, int maximumLinksPerContainer,
                                      int maximumStructureSignatureDepth,
                                      int maximumCaptureRetries, int captureSettleMillis,
                                      int linkHarvestMinimumStructuredCandidates,
                                      int linkHarvestMaximumCandidates,
                                      java.util.List<String> linkHarvestExcludedDomains) {
        this.noResultsTexts = java.util.Collections.unmodifiableList(noResultsTexts);
        this.maximumCandidateContainers = maximumCandidateContainers;
        this.minimumContainerTextCharacters = minimumContainerTextCharacters;
        this.minimumNonLinkTextCharacters = minimumNonLinkTextCharacters;
        this.minimumRepeatedSiblingCount = minimumRepeatedSiblingCount;
        this.minimumResultStructuralConfidence = minimumResultStructuralConfidence;
        this.maximumNavigationLinkDensity = maximumNavigationLinkDensity;
        this.internalLinkWeight = internalLinkWeight;
        this.externalLinkWeight = externalLinkWeight;
        this.sameHostPenalty = sameHostPenalty;
        this.sameRegistrableDomainPenalty = sameRegistrableDomainPenalty;
        this.subdomainPenalty = subdomainPenalty;
        this.unknownDomainPenalty = unknownDomainPenalty;
        this.repeatedBlockWeight = repeatedBlockWeight;
        this.nonLinkTextWeight = nonLinkTextWeight;
        this.titleLinkWeight = titleLinkWeight;
        this.snippetPresenceWeight = snippetPresenceWeight;
        this.headingLinkWeight = headingLinkWeight;
        this.semanticMainWeight = semanticMainWeight;
        this.navigationRolePenalty = navigationRolePenalty;
        this.resultBlockSimilarityThreshold = resultBlockSimilarityThreshold;
        this.minimumDiscriminatingSignalFamilies = minimumDiscriminatingSignalFamilies;
        this.fullPageAreaRatio = fullPageAreaRatio;
        this.textLengthSaturationCharacters = textLengthSaturationCharacters;
        this.maximumContainerDomDepth = maximumContainerDomDepth;
        this.maximumCapturedContainers = maximumCapturedContainers;
        this.maximumLinksPerContainer = maximumLinksPerContainer;
        this.maximumStructureSignatureDepth = maximumStructureSignatureDepth;
        this.maximumCaptureRetries = maximumCaptureRetries;
        this.captureSettleMillis = captureSettleMillis;
        this.linkHarvestMinimumStructuredCandidates = linkHarvestMinimumStructuredCandidates;
        this.linkHarvestMaximumCandidates = linkHarvestMaximumCandidates;
        this.linkHarvestExcludedDomains = java.util.Collections.unmodifiableList(
                linkHarvestExcludedDomains == null
                        ? java.util.Collections.<String>emptyList() : linkHarvestExcludedDomains);
    }
}

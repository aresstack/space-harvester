package com.aresstack.askai.browser.search.layout;

import com.aresstack.askai.browser.render.RenderedBox;

import java.util.Collections;
import java.util.List;

/**
 * A BOUNDED, neutral projection of one mechanically considered container: enough structure,
 * geometry, link statistics and per-family scores for a model to reason about the layout and for
 * diagnostics to explain it — but NEVER raw unbounded DOM, screenshots, cookies or full page text.
 * The {@link #textExcerpt} is capped by {@code SearchDiagnosticsSettings.maximumTextExcerptCharacters}
 * at build time. Container ids are snapshot-local and meaningless without the owning
 * {@link SearchPageAnalysisArtifact#snapshotId}.
 */
public final class SearchPageContainerCandidate {

    // --- identity and hierarchy
    public final String containerId;
    public final String parentContainerId;

    // --- DOM semantics
    public final String tagName;
    public final String role;
    public final List<String> semanticFlags;

    // --- bounded text
    public final String textExcerpt;
    public final int totalTextLength;
    public final int nonLinkTextLength;
    public final int headingCount;

    // --- link statistics
    public final int linkCount;
    public final int sameHostLinkCount;
    public final int sameRegistrableDomainLinkCount;
    public final int externalDomainLinkCount;

    // --- geometry / center signals
    public final RenderedBox boundingBox;
    public final double viewportIntersectionRatio;
    public final boolean containsViewportCenter;
    public final double horizontalCenterDistance;
    public final double verticalCenterDistance;

    // --- visual signals
    public final double backgroundDistanceToParent;
    public final double backgroundDistanceToPage;
    public final String borderSummary;
    public final boolean hasBoxShadow;

    // --- structural signature
    public final String structureSignature;
    public final int similarSiblingCount;
    /** Tag-name ancestry from the root to this container, e.g. {@code body>div>main}. */
    public final String ancestrySignature;

    // --- mechanical scoring
    public final List<SearchPageSignalScore> signalScores;
    public final double totalScore;
    /** Why the mechanics did not accept this container, or empty when it was a live candidate. */
    public final String rejectionReason;

    public SearchPageContainerCandidate(String containerId, String parentContainerId, String tagName,
                                        String role, List<String> semanticFlags, String textExcerpt,
                                        int totalTextLength, int nonLinkTextLength, int headingCount,
                                        int linkCount, int sameHostLinkCount,
                                        int sameRegistrableDomainLinkCount, int externalDomainLinkCount,
                                        RenderedBox boundingBox, double viewportIntersectionRatio,
                                        boolean containsViewportCenter, double horizontalCenterDistance,
                                        double verticalCenterDistance, double backgroundDistanceToParent,
                                        double backgroundDistanceToPage, String borderSummary,
                                        boolean hasBoxShadow, String structureSignature,
                                        int similarSiblingCount, String ancestrySignature,
                                        List<SearchPageSignalScore> signalScores, double totalScore,
                                        String rejectionReason) {
        this.containerId = safe(containerId);
        this.parentContainerId = safe(parentContainerId);
        this.tagName = safe(tagName);
        this.role = safe(role);
        this.semanticFlags = semanticFlags == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(semanticFlags);
        this.textExcerpt = safe(textExcerpt);
        this.totalTextLength = totalTextLength;
        this.nonLinkTextLength = nonLinkTextLength;
        this.headingCount = headingCount;
        this.linkCount = linkCount;
        this.sameHostLinkCount = sameHostLinkCount;
        this.sameRegistrableDomainLinkCount = sameRegistrableDomainLinkCount;
        this.externalDomainLinkCount = externalDomainLinkCount;
        this.boundingBox = boundingBox == null ? new RenderedBox(0, 0, 0, 0) : boundingBox;
        this.viewportIntersectionRatio = viewportIntersectionRatio;
        this.containsViewportCenter = containsViewportCenter;
        this.horizontalCenterDistance = horizontalCenterDistance;
        this.verticalCenterDistance = verticalCenterDistance;
        this.backgroundDistanceToParent = backgroundDistanceToParent;
        this.backgroundDistanceToPage = backgroundDistanceToPage;
        this.borderSummary = safe(borderSummary);
        this.hasBoxShadow = hasBoxShadow;
        this.structureSignature = safe(structureSignature);
        this.similarSiblingCount = similarSiblingCount;
        this.ancestrySignature = safe(ancestrySignature);
        this.signalScores = signalScores == null
                ? Collections.<SearchPageSignalScore>emptyList()
                : Collections.unmodifiableList(signalScores);
        this.totalScore = totalScore;
        this.rejectionReason = safe(rejectionReason);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

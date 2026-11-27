package com.aresstack.askai.browser.search.layout;

import java.util.Collections;
import java.util.List;

/**
 * The typed, neutral analysis artifact (A4a): everything the AI layout resolver and the typed
 * diagnostics may see about ONE mechanically ununderstood (or understood) SERP snapshot. It is
 * BOUNDED by construction — a capped set of {@link SearchPageContainerCandidate} projections, capped
 * excerpts, no raw HTML, no screenshots, no cookies, no headers, no credentials.
 *
 * <p><b>Snapshot safety.</b> Every downstream decision must be bound to {@link #analysisId},
 * {@link #snapshotId} and {@link #documentFingerprint}. A model answer for one snapshot must never be
 * applied to another; the validator enforces the {@code snapshotId} match and the extractor rechecks
 * it before touching a document.</p>
 */
public final class SearchPageAnalysisArtifact {

    // --- snapshot binding
    public final String analysisId;
    public final String snapshotId;
    public final long snapshotGeneration;
    public final String documentFingerprint;

    // --- page identity
    public final String searchQuery;
    public final EngineFamily engineFamily;
    public final String pageUrl;
    public final String pageTitle;

    // --- mechanical verdict
    public final MechanicalConfidenceOutcome mechanicalOutcome;
    public final double mechanicalConfidence;
    public final List<String> mechanicallyPreferredContainerIds;

    // --- bounded candidates and diagnostics
    public final List<SearchPageContainerCandidate> containerCandidates;
    public final List<String> mechanicalRejectionReasons;
    public final List<String> captureWarnings;
    /** A stable digest of the settings snapshot that produced this artifact (no secrets). */
    public final String settingsDigest;

    public SearchPageAnalysisArtifact(String analysisId, String snapshotId, long snapshotGeneration,
                                      String documentFingerprint, String searchQuery,
                                      EngineFamily engineFamily, String pageUrl, String pageTitle,
                                      MechanicalConfidenceOutcome mechanicalOutcome,
                                      double mechanicalConfidence,
                                      List<String> mechanicallyPreferredContainerIds,
                                      List<SearchPageContainerCandidate> containerCandidates,
                                      List<String> mechanicalRejectionReasons,
                                      List<String> captureWarnings, String settingsDigest) {
        this.analysisId = safe(analysisId);
        this.snapshotId = safe(snapshotId);
        this.snapshotGeneration = snapshotGeneration;
        this.documentFingerprint = safe(documentFingerprint);
        this.searchQuery = safe(searchQuery);
        this.engineFamily = engineFamily == null ? EngineFamily.UNKNOWN : engineFamily;
        this.pageUrl = safe(pageUrl);
        this.pageTitle = safe(pageTitle);
        this.mechanicalOutcome = mechanicalOutcome == null
                ? MechanicalConfidenceOutcome.LOW_CONFIDENCE : mechanicalOutcome;
        this.mechanicalConfidence = mechanicalConfidence;
        this.mechanicallyPreferredContainerIds = unmodifiable(mechanicallyPreferredContainerIds);
        this.containerCandidates = containerCandidates == null
                ? Collections.<SearchPageContainerCandidate>emptyList()
                : Collections.unmodifiableList(containerCandidates);
        this.mechanicalRejectionReasons = unmodifiable(mechanicalRejectionReasons);
        this.captureWarnings = unmodifiable(captureWarnings);
        this.settingsDigest = safe(settingsDigest);
    }

    /** The known snapshot-local container ids this artifact exposes — the ONLY ids a model may pick. */
    public boolean knowsContainer(String containerId) {
        if (containerId == null) {
            return false;
        }
        for (SearchPageContainerCandidate candidate : containerCandidates) {
            if (candidate.containerId.equals(containerId)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> unmodifiable(List<String> value) {
        return value == null ? Collections.<String>emptyList() : Collections.unmodifiableList(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

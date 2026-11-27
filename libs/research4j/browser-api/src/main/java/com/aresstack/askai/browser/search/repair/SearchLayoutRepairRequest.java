package com.aresstack.askai.browser.search.repair;

import com.aresstack.askai.browser.search.layout.EngineFamily;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisDiagnosticArtifact;

/**
 * The bounded, model-free repair offer the sidecar returns for one low-confidence SERP snapshot. It
 * carries the neutral analysis artifact and typed diagnostics — never the full RenderedPageDocument,
 * raw HTML, Playwright objects, model configuration or secrets. The sidecar keeps the corresponding
 * document cached under {@link #attemptId} until it is applied, discarded or expires.
 */
public final class SearchLayoutRepairRequest {

    public final SearchLayoutRepairAttemptId attemptId;
    public final String query;
    public final String engineHost;
    public final EngineFamily engineFamily;

    public final String snapshotId;
    public final long snapshotGeneration;
    public final String documentFingerprint;
    /** A structure-only fingerprint binding the ticket to the current layout shape. */
    public final String layoutStructureFingerprint;

    public final SearchPageAnalysisArtifact artifact;
    public final SearchPageAnalysisDiagnosticArtifact diagnostics;

    public final long createdAtEpochMillis;
    public final long expiresAtEpochMillis;

    public SearchLayoutRepairRequest(SearchLayoutRepairAttemptId attemptId, String query,
                                     String engineHost, EngineFamily engineFamily, String snapshotId,
                                     long snapshotGeneration, String documentFingerprint,
                                     String layoutStructureFingerprint,
                                     SearchPageAnalysisArtifact artifact,
                                     SearchPageAnalysisDiagnosticArtifact diagnostics,
                                     long createdAtEpochMillis, long expiresAtEpochMillis) {
        this.attemptId = attemptId == null ? new SearchLayoutRepairAttemptId("") : attemptId;
        this.query = query == null ? "" : query;
        this.engineHost = engineHost == null ? "" : engineHost;
        this.engineFamily = engineFamily == null ? EngineFamily.UNKNOWN : engineFamily;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.snapshotGeneration = snapshotGeneration;
        this.documentFingerprint = documentFingerprint == null ? "" : documentFingerprint;
        this.layoutStructureFingerprint =
                layoutStructureFingerprint == null ? "" : layoutStructureFingerprint;
        this.artifact = artifact;
        this.diagnostics = diagnostics;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }
}

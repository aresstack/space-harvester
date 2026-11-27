package com.aresstack.askai.browser.search.repair;

import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

/**
 * What the research runtime sends to {@code web_search_apply_layout}: a decision that has ALREADY
 * passed strict validation in the runtime, bound to the attempt, snapshot and fingerprint. The
 * sidecar re-checks all of these against its cached document before applying — a validated decision
 * never bypasses the snapshot guard.
 */
public final class SearchLayoutRepairSubmission {

    public final SearchLayoutRepairAttemptId attemptId;
    public final String analysisId;
    public final String snapshotId;
    public final long snapshotGeneration;
    public final String documentFingerprint;
    public final String layoutStructureFingerprint;
    public final String settingsDigest;
    public final ValidatedSearchPageLayoutDecision decision;

    public SearchLayoutRepairSubmission(SearchLayoutRepairAttemptId attemptId, String analysisId,
                                        String snapshotId, long snapshotGeneration,
                                        String documentFingerprint, String layoutStructureFingerprint,
                                        String settingsDigest,
                                        ValidatedSearchPageLayoutDecision decision) {
        this.attemptId = attemptId == null ? new SearchLayoutRepairAttemptId("") : attemptId;
        this.analysisId = analysisId == null ? "" : analysisId;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.snapshotGeneration = snapshotGeneration;
        this.documentFingerprint = documentFingerprint == null ? "" : documentFingerprint;
        this.layoutStructureFingerprint =
                layoutStructureFingerprint == null ? "" : layoutStructureFingerprint;
        this.settingsDigest = settingsDigest == null ? "" : settingsDigest;
        this.decision = decision;
    }
}

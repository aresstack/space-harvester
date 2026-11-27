package com.aresstack.askai.browser.search.layout;

import java.util.Collections;
import java.util.List;

/**
 * A model layout decision that has PASSED strict structural validation and is therefore safe to apply
 * to the extraction — every id is a known snapshot-local container, the snapshot binding is proven and
 * at least one organic region is present. It is the only decision type the extractor accepts; a raw
 * {@link SearchPageLayoutResolutionDecision} can never reach the document. Still snapshot-bound: the
 * extractor rechecks {@link #snapshotId} before touching a document.
 */
public final class ValidatedSearchPageLayoutDecision {

    public final String analysisId;
    public final String snapshotId;
    public final long snapshotGeneration;
    public final String documentFingerprint;
    public final String settingsDigest;
    public final String primaryOrganicContainerId;
    public final List<String> organicResultContainerIds;
    public final List<String> resultBlockContainerIds;
    public final List<String> excludedContainerIds;
    public final double confidence;

    public ValidatedSearchPageLayoutDecision(String analysisId, String snapshotId,
                                             long snapshotGeneration, String documentFingerprint,
                                             String settingsDigest, String primaryOrganicContainerId,
                                             List<String> organicResultContainerIds,
                                             List<String> resultBlockContainerIds,
                                             List<String> excludedContainerIds, double confidence) {
        this.analysisId = analysisId == null ? "" : analysisId;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.snapshotGeneration = snapshotGeneration;
        this.documentFingerprint = documentFingerprint == null ? "" : documentFingerprint;
        this.settingsDigest = settingsDigest == null ? "" : settingsDigest;
        this.primaryOrganicContainerId =
                primaryOrganicContainerId == null ? "" : primaryOrganicContainerId;
        this.organicResultContainerIds = unmodifiable(organicResultContainerIds);
        this.resultBlockContainerIds = unmodifiable(resultBlockContainerIds);
        this.excludedContainerIds = unmodifiable(excludedContainerIds);
        this.confidence = confidence;
    }

    private static List<String> unmodifiable(List<String> value) {
        return value == null ? Collections.<String>emptyList() : Collections.unmodifiableList(value);
    }
}

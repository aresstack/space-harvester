package com.aresstack.askai.browser.search.layout;

import java.util.Collections;
import java.util.List;

/**
 * The RAW, parsed-but-not-yet-validated layout decision a model returned: which known containers are
 * organic result regions, which are the individual result blocks, which are explicitly non-results,
 * plus a confidence and a free-text explanation. The {@link #snapshotId} the model echoed is kept so
 * the validator can reject a decision aimed at a different snapshot. {@link #explanation} is pure
 * diagnostics and NEVER substitutes for structural validation.
 */
public final class SearchPageLayoutResolutionDecision {

    /** The analysis id the model echoes back — a small correlation binding the runtime re-checks. */
    public final String analysisId;
    public final String snapshotId;
    public final List<String> organicResultContainerIds;
    public final List<String> resultBlockContainerIds;
    public final List<String> excludedContainerIds;
    public final double confidence;
    public final String explanation;

    public SearchPageLayoutResolutionDecision(String analysisId, String snapshotId,
                                              List<String> organicResultContainerIds,
                                              List<String> resultBlockContainerIds,
                                              List<String> excludedContainerIds, double confidence,
                                              String explanation) {
        this.analysisId = analysisId == null ? "" : analysisId;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.organicResultContainerIds = unmodifiable(organicResultContainerIds);
        this.resultBlockContainerIds = unmodifiable(resultBlockContainerIds);
        this.excludedContainerIds = unmodifiable(excludedContainerIds);
        this.confidence = confidence;
        this.explanation = explanation == null ? "" : explanation;
    }

    private static List<String> unmodifiable(List<String> value) {
        return value == null ? Collections.<String>emptyList() : Collections.unmodifiableList(value);
    }
}

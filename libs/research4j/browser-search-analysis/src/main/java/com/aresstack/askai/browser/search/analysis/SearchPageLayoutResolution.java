package com.aresstack.askai.browser.search.analysis;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The mechanical layout judgement of one SERP snapshot: the chosen ORGANIC_RESULTS container (or
 * none), a coarse region classification per considered container, the normalized confidence and
 * the full score breakdowns for diagnostics. {@link #lowConfidence} means the mechanics could not
 * discriminate — in A3 that yields EXTRACTION_FAILED (next engine); the later AI layout resolver
 * plugs in exactly there.
 */
public final class SearchPageLayoutResolution {

    /** The snapshot this resolution belongs to — container ids are meaningless outside it. */
    public final String snapshotId;
    /** The chosen ORGANIC_RESULTS container id, or empty when none qualified. */
    public final String organicResultsContainerId;
    /** Normalized 0..1 structural confidence of that choice (0 when none). */
    public final double confidence;
    /** True when too few signal families discriminated or no candidate met the minimum confidence. */
    public final boolean lowConfidence;
    public final Map<String, SearchPageRegionClassification> regionByContainerId;
    /** Breakdowns of every scored candidate, best first (diagnostics). */
    public final List<HeuristicScoreBreakdown> scoredCandidates;
    /**
     * Containers that WERE scored candidates but fell off the end of the candidate cap. They are the only
     * way to tell "this container was never offered because the cap cut it" apart from "it never qualified
     * as a candidate at all" — a distinction that decides where a layout contract is actually broken.
     */
    public final List<String> cappedCandidateIds;

    public SearchPageLayoutResolution(String snapshotId, String organicResultsContainerId,
                                      double confidence, boolean lowConfidence,
                                      Map<String, SearchPageRegionClassification> regionByContainerId,
                                      List<HeuristicScoreBreakdown> scoredCandidates) {
        this(snapshotId, organicResultsContainerId, confidence, lowConfidence, regionByContainerId,
                scoredCandidates, java.util.Collections.<String>emptyList());
    }

    public SearchPageLayoutResolution(String snapshotId, String organicResultsContainerId,
                                      double confidence, boolean lowConfidence,
                                      Map<String, SearchPageRegionClassification> regionByContainerId,
                                      List<HeuristicScoreBreakdown> scoredCandidates,
                                      List<String> cappedCandidateIds) {
        this.cappedCandidateIds = Collections.unmodifiableList(cappedCandidateIds);
        this.snapshotId = snapshotId;
        this.organicResultsContainerId =
                organicResultsContainerId == null ? "" : organicResultsContainerId;
        this.confidence = confidence;
        this.lowConfidence = lowConfidence;
        this.regionByContainerId = Collections.unmodifiableMap(regionByContainerId);
        this.scoredCandidates = Collections.unmodifiableList(scoredCandidates);
    }

    public boolean hasOrganicResultsContainer() {
        return !organicResultsContainerId.isEmpty();
    }
}

package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * The typed result of one full mechanical SERP extraction: the honest outcome, the candidates (only
 * for {@link SearchPageAnalysisOutcome#ORGANIC_RESULTS}) and bounded diagnostics. There is NO path
 * from any outcome to a flat link list.
 */
public final class SearchResultExtractionResult {

    public final SearchPageAnalysisOutcome outcome;
    public final String snapshotId;
    /** Normalized structural confidence of the layout resolution (0 when failed). */
    public final double layoutConfidence;
    public final List<SearchResultCandidate> candidates;
    /** Readable per-step diagnostics (rejection reasons, warnings) — bounded by the caller. */
    public final List<String> diagnostics;

    public SearchResultExtractionResult(SearchPageAnalysisOutcome outcome, String snapshotId,
                                        double layoutConfidence,
                                        List<SearchResultCandidate> candidates,
                                        List<String> diagnostics) {
        this.outcome = outcome;
        this.snapshotId = snapshotId;
        this.layoutConfidence = layoutConfidence;
        this.candidates = Collections.unmodifiableList(candidates);
        this.diagnostics = Collections.unmodifiableList(diagnostics);
    }
}

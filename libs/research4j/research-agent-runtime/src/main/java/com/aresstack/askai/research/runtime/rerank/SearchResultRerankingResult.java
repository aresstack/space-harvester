package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The full, typed outcome of one mandatory reranking attempt for a query. Beyond the reranked order and
 * the selected survivors it carries the endpoint provenance ({@link #modelName}, {@link #scoreSemantics})
 * and the runtime's own {@link #totalDurationNanos}/{@link #loadDurationNanos} plus human diagnostics —
 * so a failure is fully attributable and never masquerades as "no relevant paths". The selected list is
 * non-empty only when {@link #outcome} is {@link SearchResultRerankingOutcome#SUCCESS}.
 */
public final class SearchResultRerankingResult {

    public final SearchResultRerankingOutcome outcome;
    public final List<RerankedSearchResultCandidate> reranked;
    public final List<RerankedSearchResultCandidate> selected;
    public final String modelName;
    public final RerankerScoreSemantics scoreSemantics;
    public final String diagnostics;
    public final long totalDurationNanos;
    public final long loadDurationNanos;

    public SearchResultRerankingResult(SearchResultRerankingOutcome outcome,
                                       List<RerankedSearchResultCandidate> reranked,
                                       List<RerankedSearchResultCandidate> selected,
                                       String modelName, RerankerScoreSemantics scoreSemantics,
                                       String diagnostics, long totalDurationNanos,
                                       long loadDurationNanos) {
        this.outcome = outcome;
        this.reranked = Collections.unmodifiableList(
                new ArrayList<RerankedSearchResultCandidate>(reranked));
        this.selected = Collections.unmodifiableList(
                new ArrayList<RerankedSearchResultCandidate>(selected));
        this.modelName = modelName == null ? "" : modelName;
        this.scoreSemantics = scoreSemantics;
        this.diagnostics = diagnostics == null ? "" : diagnostics;
        this.totalDurationNanos = totalDurationNanos;
        this.loadDurationNanos = loadDurationNanos;
    }

    /** A terminal failure/empty outcome carrying no candidates (endpoint metadata may still be present). */
    public static SearchResultRerankingResult failure(SearchResultRerankingOutcome outcome,
                                                      String modelName,
                                                      RerankerScoreSemantics scoreSemantics,
                                                      String diagnostics) {
        List<RerankedSearchResultCandidate> empty = new ArrayList<RerankedSearchResultCandidate>();
        return new SearchResultRerankingResult(outcome, empty, empty, modelName, scoreSemantics,
                diagnostics, 0L, 0L);
    }
}

package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.domain.search.RelevanceAssessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plan PR 14 (D5): the ranking of a planned query. It ranks with the chosen ranking (the installed cross-encoder)
 * and keeps the top N; when that ranking is unavailable for a call (reranker stopped, timed out, misconfigured or
 * not installed), the call is ranked by BM25 instead and {@link #fellBackToKeywords()} says so. It never falls back
 * to engine order. With no primary at all, every call is ranked by BM25 and reported as a fallback.
 */
public final class PlannedQueryReranker implements CandidateReranker {

    private final CandidateReranker primary;
    private final CandidateReranker keywords;
    private final int visitLimit;
    private final boolean keywordsChosen;
    private final AtomicBoolean fellBack = new AtomicBoolean();
    private volatile SourcePreferenceRanking preference = SourcePreferenceRanking.NONE;

    /**
     * @param primary    the chosen ranking, {@code null} when it could not be built
     * @param keywords   the BM25 ranking
     * @param visitLimit how many selected hits are kept (0 = no limit)
     */
    public static PlannedQueryReranker ranked(CandidateReranker primary, CandidateReranker keywords,
                                              int visitLimit) {
        PlannedQueryReranker reranker = new PlannedQueryReranker(primary, keywords, visitLimit, false);
        if (primary == null) {
            reranker.fellBack.set(true);
        }
        return reranker;
    }

    /** BM25 was chosen: never a fallback. */
    public static PlannedQueryReranker keywords(CandidateReranker keywords, int visitLimit) {
        return new PlannedQueryReranker(null, keywords, visitLimit, true);
    }

    private PlannedQueryReranker(CandidateReranker primary, CandidateReranker keywords, int visitLimit,
                                 boolean keywordsChosen) {
        if (keywords == null) {
            throw new IllegalArgumentException("the BM25 ranking is required");
        }
        this.primary = primary;
        this.keywords = keywords;
        this.visitLimit = Math.max(0, visitLimit);
        this.keywordsChosen = keywordsChosen;
    }

    /**
     * The mission's source preference: it weighs the selected hits before the top N are kept, so a preferred
     * publisher just below the cut is visited instead of a vendor page above it.
     */
    public PlannedQueryReranker withSourcePreference(SourcePreferenceRanking ranking) {
        this.preference = ranking == null ? SourcePreferenceRanking.NONE : ranking;
        return this;
    }

    /** Whether a call of this search was ranked by BM25 because the chosen ranking was unavailable. */
    public boolean fellBackToKeywords() {
        return !keywordsChosen && fellBack.get();
    }

    @Override
    public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                              CancellationSignal cancellation) {
        SearchResultRerankingResult result = null;
        if (primary != null) {
            try {
                result = primary.rerank(query, candidates, cancellation);
            } catch (RuntimeException failed) {
                result = null;
            }
            if (result == null || unavailable(result.outcome)) {
                fellBack.set(true);
                result = null;
            }
        }
        if (result == null) {
            result = keywords.rerank(query, candidates, cancellation);
        }
        return limited(result);
    }

    @Override
    public RelevanceAssessment assess(String query, LinkedHashMap<String, String> documentsById,
                                      CancellationSignal cancellation) {
        if (primary != null) {
            try {
                RelevanceAssessment assessment = primary.assess(query, documentsById, cancellation);
                if (assessment != null && assessment.isAvailable()) {
                    return assessment;
                }
            } catch (RuntimeException failed) {
                // ranked by keywords below
            }
            fellBack.set(true);
        }
        return keywords.assess(query, documentsById, cancellation);
    }

    static boolean unavailable(SearchResultRerankingOutcome outcome) {
        return outcome == SearchResultRerankingOutcome.RERANKER_UNAVAILABLE
                || outcome == SearchResultRerankingOutcome.INVALID_RESPONSE
                || outcome == SearchResultRerankingOutcome.TIMEOUT
                || outcome == SearchResultRerankingOutcome.CONFIGURATION_ERROR
                || outcome == SearchResultRerankingOutcome.BUDGET_EXHAUSTED;
    }

    private SearchResultRerankingResult limited(SearchResultRerankingResult result) {
        SourcePreferenceRanking p = preference;
        if (!p.isNone() && result.selected.size() > 1) {
            result = new SearchResultRerankingResult(result.outcome, result.reranked, p.apply(result.selected),
                    result.modelName, result.scoreSemantics, result.diagnostics + " (weighed by source preference)",
                    result.totalDurationNanos, result.loadDurationNanos);
        }
        if (visitLimit <= 0 || result.selected.size() <= visitLimit) {
            return result;
        }
        List<RerankedSearchResultCandidate> top =
                new ArrayList<RerankedSearchResultCandidate>(result.selected.subList(0, visitLimit));
        return new SearchResultRerankingResult(result.outcome, result.reranked, top, result.modelName,
                result.scoreSemantics, result.diagnostics, result.totalDurationNanos, result.loadDurationNanos);
    }
}

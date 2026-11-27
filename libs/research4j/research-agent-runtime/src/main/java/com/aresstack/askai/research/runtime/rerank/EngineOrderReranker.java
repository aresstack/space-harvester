package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * An EXPLICITLY named pass-through reranker that keeps the search engine's original candidate order. It
 * exists solely so tests which do not exercise a real reranker can state that intent by injecting this
 * adapter — production never uses it. Because it is a named object rather than a {@code null}, the loop
 * never has to (and never does) infer "engine order" from the absence of a reranker; the productive path
 * fails earlier, at session start, when the mandatory reranker snapshot is missing.
 */
public final class EngineOrderReranker implements CandidateReranker {

    @Override
    public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                              CancellationSignal cancellation) {
        List<RerankedSearchResultCandidate> ordered =
                new ArrayList<RerankedSearchResultCandidate>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            ordered.add(new RerankedSearchResultCandidate(candidates.get(i), 0.0, i + 1));
        }
        SearchResultRerankingOutcome outcome = ordered.isEmpty()
                ? SearchResultRerankingOutcome.NO_CANDIDATES : SearchResultRerankingOutcome.SUCCESS;
        return new SearchResultRerankingResult(outcome, ordered, ordered,
                "engine-order (test adapter)", RerankerScoreSemantics.RAW_LOGIT,
                "engine order preserved; no reranking performed", 0L, 0L);
    }

    /**
     * No semantic capability, and it says so. A test adapter that invented scores here would let the
     * acquisition believe it had judged relevance when it had only preserved an order.
     */
    @Override
    public com.aresstack.askai.research.domain.search.RelevanceAssessment assess(
            String query, java.util.LinkedHashMap<String, String> documentsById,
            CancellationSignal cancellation) {
        return com.aresstack.askai.research.domain.search.RelevanceAssessment
                .unavailable("engine-order adapter has no relevance model");
    }
}

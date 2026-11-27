package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.util.List;

/**
 * The mandatory reranking seam between structured extraction and browser navigation. The productive
 * implementation is the HTTP-backed {@link SearchResultReranker}; tests that deliberately do not exercise
 * a real reranker inject the explicitly named {@link EngineOrderReranker} rather than relying on a null
 * reranker — production code never infers "engine order" from the ABSENCE of a reranker.
 */
public interface CandidateReranker {

    /**
     * Rerank the organic candidates for the query and select the survivors. Never throws for a reranker
     * failure — every outcome (unavailable, invalid, timeout, cancelled, no matches) is reported through
     * the returned {@link SearchResultRerankingResult#outcome}.
     */
    SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                       CancellationSignal cancellation);

    /**
     * Score arbitrary documents against the query — RELEVANCE only, no selection and no threshold.
     * <p>
     * The same semantic capability the SERP reranking uses, made available to the rest of the
     * acquisition. Relevance did not stop being a question once a hit left the result page: a loaded
     * page and a link found on it are just as much "how good is this for this query", and answering
     * them with a substring test is what let a noodle recipe into a search about rabbit steaks.
     * <p>
     * Selection stays out of it deliberately (see {@code RelevanceAssessment}): what to DO with a score
     * is the caller's policy. An unavailable reranker yields an unavailable assessment — a fact to act
     * on, never a fabricated score.
     *
     * @param documentsById insertion-ordered id → document text; ids are the caller's own
     */
    com.aresstack.askai.research.domain.search.RelevanceAssessment assess(
            String query, java.util.LinkedHashMap<String, String> documentsById,
            CancellationSignal cancellation);
}

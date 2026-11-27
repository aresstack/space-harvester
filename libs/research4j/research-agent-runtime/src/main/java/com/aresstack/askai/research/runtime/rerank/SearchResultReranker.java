package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * A5c orchestrator: the MANDATORY reranking step between structured search extraction and browser
 * navigation. It turns each organic candidate into the text the cross-encoder scores — {@code Title:
 * …\nSnippet: …}, deliberately NOT the URL, so relevance is judged on content and not on domain
 * spelling — scores them all against the query, maps the RAW_LOGIT scores back onto the candidates and
 * hands them to the {@link SearchResultSelectionPolicy}. It NEVER falls back to raw engine order and
 * never throws for a reranker failure: every outcome (unavailable, invalid, timeout, cancelled, no
 * matches) is reported through the returned {@link SearchResultRerankingResult#outcome}.
 */
public final class SearchResultReranker implements CandidateReranker {

    private final HttpRerankerClient client;
    private final SearchResultSelectionPolicy policy;
    private final String modelName;
    private final RerankerScoreSemantics scoreSemantics;

    public SearchResultReranker(HttpRerankerClient client, SearchResultSelectionPolicy policy) {
        this(client, policy, "", RerankerScoreSemantics.RAW_LOGIT);
    }

    public SearchResultReranker(HttpRerankerClient client, SearchResultSelectionPolicy policy,
                                String modelName, RerankerScoreSemantics scoreSemantics) {
        this.client = client;
        this.policy = policy;
        this.modelName = modelName;
        this.scoreSemantics = scoreSemantics;
    }

    /** The document text a candidate is scored by: its title and snippet, never its URL. */
    public static String documentText(SearchResultCandidate candidate) {
        return "Title: " + candidate.title + "\nSnippet: " + candidate.snippet;
    }

    @Override
    public SearchResultRerankingResult rerank(String query, List<SearchResultCandidate> candidates,
                                              CancellationSignal cancellation) {
        if (candidates.isEmpty()) {
            return SearchResultRerankingResult.failure(SearchResultRerankingOutcome.NO_CANDIDATES,
                    modelName, scoreSemantics, "no organic candidates to rerank");
        }
        List<String> documents = new ArrayList<String>(candidates.size());
        for (SearchResultCandidate candidate : candidates) {
            documents.add(documentText(candidate));
        }

        RerankResponse response;
        try {
            response = client.rerank(query, documents, cancellation);
        } catch (RerankerClientException ex) {
            return SearchResultRerankingResult.failure(mapFailure(ex.getFailure()), modelName,
                    scoreSemantics, ex.getMessage());
        }

        // Map each scored row back onto its candidate (the client guarantees completeness + valid indices).
        List<RerankedSearchResultCandidate> scored =
                new ArrayList<RerankedSearchResultCandidate>(response.scores.size());
        for (RerankScore row : response.scores) {
            scored.add(new RerankedSearchResultCandidate(
                    candidates.get(row.documentIndex), row.score, 0));
        }
        SearchResultSelection selection = policy.select(scored);
        String diagnostics = "reranked " + candidates.size() + " candidates → "
                + selection.selected.size() + " selected (" + selection.outcome + ")";
        return new SearchResultRerankingResult(selection.outcome, selection.reranked,
                selection.selected, response.model.isEmpty() ? modelName : response.model,
                scoreSemantics, diagnostics, response.totalDurationNanos, response.loadDurationNanos);
    }

    @Override
    public com.aresstack.askai.research.domain.search.RelevanceAssessment assess(
            String query, java.util.LinkedHashMap<String, String> documentsById,
            CancellationSignal cancellation) {
        if (documentsById == null || documentsById.isEmpty()) {
            return com.aresstack.askai.research.domain.search.RelevanceAssessment
                    .unavailable("no documents to assess");
        }
        List<String> ids = new ArrayList<String>(documentsById.keySet());
        List<String> documents = new ArrayList<String>(documentsById.values());
        RerankResponse response;
        try {
            response = client.rerank(query, documents, cancellation);
        } catch (RerankerClientException ex) {
            return com.aresstack.askai.research.domain.search.RelevanceAssessment
                    .unavailable(ex.getFailure() + ": " + ex.getMessage());
        }
        List<com.aresstack.askai.research.domain.search.RelevanceAssessment.Score> scores =
                new ArrayList<com.aresstack.askai.research.domain.search.RelevanceAssessment.Score>();
        for (RerankScore row : response.scores) {
            scores.add(new com.aresstack.askai.research.domain.search.RelevanceAssessment.Score(
                    ids.get(row.documentIndex), row.score));
        }
        return com.aresstack.askai.research.domain.search.RelevanceAssessment.of(
                response.model.isEmpty() ? modelName : response.model, scores);
    }

    private static SearchResultRerankingOutcome mapFailure(RerankerClientFailure failure) {
        switch (failure) {
            case TIMEOUT:
                return SearchResultRerankingOutcome.TIMEOUT;
            case CANCELLED:
                return SearchResultRerankingOutcome.CANCELLED;
            case INVALID_RESPONSE:
                return SearchResultRerankingOutcome.INVALID_RESPONSE;
            case TRANSPORT:
            case HTTP_STATUS:
            default:
                return SearchResultRerankingOutcome.RERANKER_UNAVAILABLE;
        }
    }
}

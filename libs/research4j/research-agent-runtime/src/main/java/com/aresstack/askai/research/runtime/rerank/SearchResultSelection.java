package com.aresstack.askai.research.runtime.rerank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The pure output of the {@link SearchResultSelectionPolicy}: the full reranked order (all scored
 * candidates, most relevant first) and the selected survivors admitted for navigation, plus the outcome
 * that distinguishes SUCCESS from NO_CANDIDATES / NO_SEMANTIC_MATCHES. It carries no endpoint metadata —
 * {@link SearchResultReranker} wraps it into the public {@link SearchResultRerankingResult}.
 */
public final class SearchResultSelection {

    public final SearchResultRerankingOutcome outcome;
    public final List<RerankedSearchResultCandidate> reranked;
    public final List<RerankedSearchResultCandidate> selected;

    public SearchResultSelection(SearchResultRerankingOutcome outcome,
                                 List<RerankedSearchResultCandidate> reranked,
                                 List<RerankedSearchResultCandidate> selected) {
        this.outcome = outcome;
        this.reranked = Collections.unmodifiableList(
                new ArrayList<RerankedSearchResultCandidate>(reranked));
        this.selected = Collections.unmodifiableList(
                new ArrayList<RerankedSearchResultCandidate>(selected));
    }
}

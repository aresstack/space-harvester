package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;

/**
 * A search candidate paired with the RAW_LOGIT relevance score the cross-encoder gave it for the
 * current query, plus its 1-based rank in the reranked order (1 = most relevant). The score is
 * UNBOUNDED and may be negative; ranks come from sorting by score, never from a fixed threshold.
 */
public final class RerankedSearchResultCandidate {

    public final SearchResultCandidate candidate;
    public final double score;
    public final int rerankRank;

    public RerankedSearchResultCandidate(SearchResultCandidate candidate, double score,
                                         int rerankRank) {
        this.candidate = candidate;
        this.score = score;
        this.rerankRank = rerankRank;
    }
}

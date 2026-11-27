package com.aresstack.askai.research.runtime.rerank;

/**
 * One validated row of a rerank response: the 0-based index of a submitted document and its RAW_LOGIT
 * relevance score. Scores are UNBOUNDED and may be negative; higher means more relevant. There is no
 * fixed 0.5 threshold — the best hit for a query can legitimately be a small or negative logit.
 */
public final class RerankScore {

    public final int documentIndex;
    public final double score;

    public RerankScore(int documentIndex, double score) {
        this.documentIndex = documentIndex;
        this.score = score;
    }
}

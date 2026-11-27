package com.aresstack.askai.agent.model.reranker;

/**
 * How to READ a reranker score. The R0 cross-encoder returns {@link #RAW_LOGIT}s — unbounded, often
 * negative, and NOT probabilities: a clearly best hit may score only 0.12. The selection policy must
 * therefore never assume a fixed threshold like 0.5, never apply a sigmoid silently and never present
 * a raw logit as a probability. Higher score = more relevant, always.
 */
public enum RerankerScoreSemantics {
    /** Unbounded model logits; compare by ordering and configured relative rules, never a fixed cutoff. */
    RAW_LOGIT,
    /** Already squashed to 0..1 by the provider (a future remote model); still ordered high-to-low. */
    SIGMOID_NORMALIZED
}

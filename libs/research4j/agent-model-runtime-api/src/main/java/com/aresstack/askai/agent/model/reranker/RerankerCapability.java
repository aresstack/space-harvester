package com.aresstack.askai.agent.model.reranker;

/**
 * A capability a model endpoint advertises. A5 requires exactly {@link #RERANK}; the enum exists so a
 * descriptor can be validated against a required capability rather than a free-text tag.
 */
public enum RerankerCapability {
    RERANK
}

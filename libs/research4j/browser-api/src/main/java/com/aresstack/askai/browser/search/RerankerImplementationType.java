package com.aresstack.askai.browser.search;

/** How the result reranker is implemented. Only the contract exists until the reranker slice ships. */
public enum RerankerImplementationType {
    HEURISTIC, EMBEDDING, CROSS_ENCODER, LLM
}

package com.aresstack.knowledge4j.policy;

/** What leaves the library through an exit. */
public enum PayloadKind {
    SEARCH_QUERY,
    CONTENT_FETCH,
    MODEL_PROMPT,
    TOOL_CALL,
    EMBEDDING,
    RERANK,
    INFORMATION_NEED,
    DIAGNOSTICS,
    CACHE_ENTRY
}

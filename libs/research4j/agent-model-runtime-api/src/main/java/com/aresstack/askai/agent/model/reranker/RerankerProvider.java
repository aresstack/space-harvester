package com.aresstack.askai.agent.model.reranker;

/**
 * Who serves a reranker endpoint. {@link #ASKAI_LOCAL} is the AskAI-managed local model runtime
 * (the R0 sidecar) reached over plain HTTP; the enum leaves room for future remote providers without
 * a magic string. The descriptor never carries process paths or a way to START a runtime — only how
 * to REACH an already-available endpoint.
 */
public enum RerankerProvider {
    ASKAI_LOCAL,
    /**
     * An OpenAI-compatible API's {@code POST <baseUrl>/rerank} in the common format (vLLM, llama.cpp, Jina, Cohere):
     * request {@code model}, {@code query}, {@code documents}, {@code top_n}; answer {@code results[]} with
     * {@code index} and {@code relevance_score} (or {@code score}). The key comes per request through
     * {@link com.aresstack.askai.agent.model.EndpointAuthorization}.
     */
    OPENAI_COMPATIBLE
}

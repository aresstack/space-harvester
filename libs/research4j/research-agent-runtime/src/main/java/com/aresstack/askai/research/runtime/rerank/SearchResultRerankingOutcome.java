package com.aresstack.askai.research.runtime.rerank;

/**
 * The typed result of one mandatory reranking attempt. Every non-{@link #SUCCESS} value is honest about
 * WHY no selected candidates are available, so the loop can map it to a distinct stop reason instead of
 * collapsing a reranker failure into "no relevant paths".
 */
public enum SearchResultRerankingOutcome {

    /** Candidates were scored and at least one survived selection — safe to open the selection. */
    SUCCESS,

    /** There were no organic candidates to rerank (empty search result). */
    NO_CANDIDATES,

    /** Candidates were scored, but none passed the selection policy (all below the configured cut-offs). */
    NO_SEMANTIC_MATCHES,

    /** The reranker endpoint could not be reached, or answered with a non-2xx status. */
    RERANKER_UNAVAILABLE,

    /** The reranker answered but the body violated the strict contract. */
    INVALID_RESPONSE,

    /** The reranker call exceeded its timeout. */
    TIMEOUT,

    /** The run was cancelled before or during the reranking call. */
    CANCELLED,

    /** The tool budget was exhausted before the reranking call could run. */
    BUDGET_EXHAUSTED,

    /** The reranker configuration/snapshot was missing or invalid. */
    CONFIGURATION_ERROR
}

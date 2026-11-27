package com.aresstack.askai.research.runtime.rerank;

/**
 * Why a reranker call did not yield a usable, trustworthy result. Each value is a HARD failure — the
 * research loop must NOT silently fall back to opening pages in raw engine order on any of these; the
 * mandatory reranking step failed and that must be visible.
 */
public enum RerankerClientFailure {

    /** The endpoint could not be reached or the connection broke mid-call. */
    TRANSPORT,

    /** The connect/read on the endpoint exceeded the configured timeout. */
    TIMEOUT,

    /** The endpoint answered with a non-2xx HTTP status (including its {@code {"error":…}} body). */
    HTTP_STATUS,

    /**
     * The endpoint answered 2xx but the body is not a contract-valid rerank response: not JSON,
     * missing {@code results}, a non-finite (NaN/Infinity) score, a duplicated document index, or an
     * index outside the submitted document range. Never guessed around — a scrambled ranking is worse
     * than a visible failure.
     */
    INVALID_RESPONSE,

    /** The caller cancelled the run before/while the call completed. */
    CANCELLED
}

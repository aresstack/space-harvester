package com.aresstack.askai.research.runtime.rerank;

/** A typed, non-recoverable failure of a reranker call — carries the {@link RerankerClientFailure}. */
public final class RerankerClientException extends Exception {

    private final RerankerClientFailure failure;

    public RerankerClientException(RerankerClientFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public RerankerClientException(RerankerClientFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public RerankerClientFailure getFailure() {
        return failure;
    }
}

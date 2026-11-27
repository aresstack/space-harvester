package com.aresstack.askai.browser.search;

/**
 * Retry contract for an AI call (layout resolver, reranker). A retry NEVER blindly repeats the same
 * prompt: repair attempts receive the previous response and the concrete validation errors when the
 * include* flags are set. Unbounded retries are impossible by construction — {@code maximumAttempts}
 * is validated to a hard range.
 */
public final class AiRetryPolicy {

    public final int maximumAttempts;
    public final int initialBackoffMillis;
    public final double backoffMultiplier;
    public final int maximumBackoffMillis;

    public final boolean retryOnEmptyResponse;
    public final boolean retryOnParsingFailure;
    public final boolean retryOnSchemaViolation;
    public final boolean retryOnUnknownIds;
    public final boolean retryOnSemanticValidationFailure;
    public final boolean retryOnModelTimeout;

    /** Repair context: hand the model its previous (bad) response on the next attempt. */
    public final boolean includePreviousResponse;
    /** Repair context: hand the model the concrete validation errors on the next attempt. */
    public final boolean includeValidationErrors;

    public AiRetryPolicy(int maximumAttempts, int initialBackoffMillis, double backoffMultiplier,
                         int maximumBackoffMillis, boolean retryOnEmptyResponse,
                         boolean retryOnParsingFailure, boolean retryOnSchemaViolation,
                         boolean retryOnUnknownIds, boolean retryOnSemanticValidationFailure,
                         boolean retryOnModelTimeout, boolean includePreviousResponse,
                         boolean includeValidationErrors) {
        this.maximumAttempts = maximumAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.backoffMultiplier = backoffMultiplier;
        this.maximumBackoffMillis = maximumBackoffMillis;
        this.retryOnEmptyResponse = retryOnEmptyResponse;
        this.retryOnParsingFailure = retryOnParsingFailure;
        this.retryOnSchemaViolation = retryOnSchemaViolation;
        this.retryOnUnknownIds = retryOnUnknownIds;
        this.retryOnSemanticValidationFailure = retryOnSemanticValidationFailure;
        this.retryOnModelTimeout = retryOnModelTimeout;
        this.includePreviousResponse = includePreviousResponse;
        this.includeValidationErrors = includeValidationErrors;
    }
}

package com.aresstack.askai.browser.search.inference;

/**
 * The typed outcome of a single {@link StructuredInferencePort} call. There is NO silent fake
 * success: when no model adapter is wired the productive port answers {@link #UNAVAILABLE} and the
 * search pipeline falls back through its existing engine policy.
 */
public enum StructuredInferenceStatus {
    /** A response was produced; {@code rawText} carries it (still unvalidated). */
    SUCCESS,
    /** No adapter/model is available to serve the request. */
    UNAVAILABLE,
    /** The adapter was reached but did not answer within its budget. */
    TIMEOUT,
    /** The caller's cancellation signal fired before or during the call. */
    CANCELLED,
    /** The adapter answered but the payload was structurally unusable (e.g. empty). */
    INVALID_RESPONSE,
    /** The adapter failed for a provider-side reason (transport, quota, internal error). */
    PROVIDER_FAILURE
}

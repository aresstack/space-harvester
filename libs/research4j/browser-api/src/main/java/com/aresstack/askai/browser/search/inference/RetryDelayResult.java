package com.aresstack.askai.browser.search.inference;

/**
 * The outcome of waiting out a repair backoff: the delay elapsed, or the cancellation signal fired
 * during the wait.
 */
public enum RetryDelayResult {
    COMPLETED,
    CANCELLED
}

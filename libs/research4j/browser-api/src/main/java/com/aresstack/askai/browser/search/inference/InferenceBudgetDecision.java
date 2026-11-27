package com.aresstack.askai.browser.search.inference;

/**
 * The typed verdict of an {@link InferenceBudgetGate} check made BEFORE an inference (initial or
 * repair) call. Only {@link #ALLOWED} lets the call proceed; every other value stops the resolver
 * without a model call and routes back to the existing engine-fallback policy.
 */
public enum InferenceBudgetDecision {
    ALLOWED,
    CANCELLED,
    BUDGET_EXHAUSTED,
    TIME_EXHAUSTED
}

package com.aresstack.askai.browser.search.inference;

/**
 * A neutral budget gate consulted BEFORE every inference call (initial and each repair). The layout
 * resolver never sees the research run budget; the runtime adapts its central token/time/attempt
 * budget onto this port. {@link #ALLOW_ALL} authorizes everything — the safe default for tests and
 * for contexts without a budget.
 */
public interface InferenceBudgetGate {

    InferenceBudgetGate ALLOW_ALL = new InferenceBudgetGate() {
        public InferenceBudgetDecision beforeInference(InferenceBudgetRequest request) {
            return InferenceBudgetDecision.ALLOWED;
        }
    };

    InferenceBudgetDecision beforeInference(InferenceBudgetRequest request);
}

package com.aresstack.askai.browser.search.inference;

/**
 * What an {@link InferenceBudgetGate} is asked to authorize: which snapshot the call belongs to, the
 * 1-based attempt number, whether it is a repair attempt and the requested output-token ceiling. It
 * carries no runtime type — the research runtime adapts its central budget to this neutral request.
 */
public final class InferenceBudgetRequest {

    public final String snapshotId;
    public final int attemptNumber;
    public final boolean repair;
    public final int maximumOutputTokens;

    public InferenceBudgetRequest(String snapshotId, int attemptNumber, boolean repair,
                                  int maximumOutputTokens) {
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.attemptNumber = attemptNumber;
        this.repair = repair;
        this.maximumOutputTokens = maximumOutputTokens;
    }
}

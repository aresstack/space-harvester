package com.aresstack.askai.research.runtime.loop;

/** The hard limits of one autonomous research run. Immutable; MVP defaults per the work order. */
public final class ResearchRunBudget {

    private final int maxToolCalls;
    private final int maxPagesVisited;
    private final int maxAcceptedSources;
    private final int maxConsecutiveErrors;
    private final long maxDurationMillis;
    private final int minimumAcceptedSources;
    private final int minimumDistinctHosts;

    public ResearchRunBudget(int maxToolCalls, int maxPagesVisited, int maxAcceptedSources,
                             int maxConsecutiveErrors, long maxDurationMillis,
                             int minimumAcceptedSources, int minimumDistinctHosts) {
        this.maxToolCalls = maxToolCalls;
        this.maxPagesVisited = maxPagesVisited;
        this.maxAcceptedSources = maxAcceptedSources;
        this.maxConsecutiveErrors = maxConsecutiveErrors;
        this.maxDurationMillis = maxDurationMillis;
        this.minimumAcceptedSources = minimumAcceptedSources;
        this.minimumDistinctHosts = minimumDistinctHosts;
    }

    public static ResearchRunBudget defaults() {
        return new ResearchRunBudget(30, 20, 8, 3, 10L * 60 * 1000, 3, 2);
    }

    public int getMaxToolCalls() { return maxToolCalls; }
    public int getMaxPagesVisited() { return maxPagesVisited; }
    public int getMaxAcceptedSources() { return maxAcceptedSources; }
    public int getMaxConsecutiveErrors() { return maxConsecutiveErrors; }
    public long getMaxDurationMillis() { return maxDurationMillis; }
    public int getMinimumAcceptedSources() { return minimumAcceptedSources; }
    public int getMinimumDistinctHosts() { return minimumDistinctHosts; }
}

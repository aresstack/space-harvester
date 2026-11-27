package com.aresstack.askai.research.runtime.loop;

/**
 * The structured, user-facing result of one autonomous research run: what was achieved, why it stopped,
 * whether the state is recoverable, which evidence requirement is still open and what the agent recommends
 * next. This object (not any log line) is the ONLY basis for the result card the UI shows — the tokens are
 * machine-readable and localized host-side.
 */
public final class ResearchRunOutcome {

    /** Which evidence requirement is still unmet (evaluated against the run budget's minimums). */
    public enum Limitation { NONE, INSUFFICIENT_SOURCES, INSUFFICIENT_HOST_DIVERSITY }

    /** What the agent recommends as the next step (the UI renders matching actions). */
    public enum RecommendedAction {
        REVIEW_EVIDENCE, CONTINUE_RESEARCH, REFINE_RESEARCH_SCOPE, RETRY,
        /** Fix the research runtime configuration (e.g. an invalid reranker selection) first. */
        OPEN_CONFIGURATION, NONE
    }

    private final ResearchStopReason stopReason;
    private final int pagesVisited;
    private final int acceptedSources;
    private final int distinctHosts;
    private final int minimumSources;
    private final int minimumDistinctHosts;
    private final boolean recoverable;
    private final Limitation limitation;
    private final RecommendedAction recommendedAction;

    public ResearchRunOutcome(ResearchStopReason stopReason, int pagesVisited, int acceptedSources,
                              int distinctHosts, int minimumSources, int minimumDistinctHosts,
                              boolean recoverable, Limitation limitation, RecommendedAction recommendedAction) {
        this.stopReason = stopReason;
        this.pagesVisited = pagesVisited;
        this.acceptedSources = acceptedSources;
        this.distinctHosts = distinctHosts;
        this.minimumSources = minimumSources;
        this.minimumDistinctHosts = minimumDistinctHosts;
        this.recoverable = recoverable;
        this.limitation = limitation;
        this.recommendedAction = recommendedAction;
    }

    /** Derive the outcome from the run's final progress against its budget. */
    public static ResearchRunOutcome from(ResearchStopReason reason, ResearchRunProgress progress,
                                          ResearchRunBudget budget) {
        Limitation limitation;
        if (progress.getAcceptedSources() < budget.getMinimumAcceptedSources()) {
            limitation = Limitation.INSUFFICIENT_SOURCES;
        } else if (progress.getDistinctHosts().size() < budget.getMinimumDistinctHosts()) {
            limitation = Limitation.INSUFFICIENT_HOST_DIVERSITY;
        } else {
            limitation = Limitation.NONE;
        }
        RecommendedAction action;
        boolean recoverable = true;
        switch (reason) {
            case SUFFICIENT_EVIDENCE:
            case SOURCE_BUDGET_EXHAUSTED:
                action = RecommendedAction.REVIEW_EVIDENCE;
                break;
            case TOOL_BUDGET_EXHAUSTED:
            case PAGE_BUDGET_EXHAUSTED:
            case TIME_BUDGET_EXHAUSTED:
                action = limitation == Limitation.NONE
                        ? RecommendedAction.REVIEW_EVIDENCE : RecommendedAction.CONTINUE_RESEARCH;
                break;
            case NO_RELEVANT_PATHS:
            // NO_SEMANTIC_MATCHES is the reranker's typed "nothing is relevant enough" — a semantic
            // result, NOT a technical failure and NEVER a generic budget stop.
            case NO_SEMANTIC_MATCHES:
                action = progress.getAcceptedSources() > 0 && limitation == Limitation.NONE
                        ? RecommendedAction.REVIEW_EVIDENCE : RecommendedAction.REFINE_RESEARCH_SCOPE;
                break;
            case MCP_UNAVAILABLE:
            case ERROR_BUDGET_EXHAUSTED:
            // A technical search failure (SERP unparseable / layout-repair model unavailable / all engines
            // blocked): retrying is meaningful once the model is back or the challenge clears — never a
            // budget stop and never an honest empty search.
            case SEARCH_TECHNICAL_PROBLEM:
            // Transient technical reranker failures: retrying is meaningful (endpoint back, timeout
            // passed, runtime restarted) — but they are never presented as budget stops.
            case RERANKER_UNAVAILABLE:
            case RERANKER_TIMEOUT:
            case RERANKER_INVALID_RESPONSE:
                action = RecommendedAction.RETRY;
                break;
            case RERANKER_CONFIGURATION_ERROR:
                // Retrying cannot help until the snapshot/selection is fixed in the settings.
                action = RecommendedAction.OPEN_CONFIGURATION;
                recoverable = false;
                break;
            case USER_CANCELLED:
            case APPROVAL_REQUIRED:
            case STATE_CHANGED:
            default:
                action = RecommendedAction.NONE;
                break;
        }
        return new ResearchRunOutcome(reason, progress.getPagesVisited(), progress.getAcceptedSources(),
                progress.getDistinctHosts().size(), budget.getMinimumAcceptedSources(),
                budget.getMinimumDistinctHosts(), recoverable, limitation, action);
    }

    public ResearchStopReason getStopReason() { return stopReason; }
    public int getPagesVisited() { return pagesVisited; }
    public int getAcceptedSources() { return acceptedSources; }
    public int getDistinctHosts() { return distinctHosts; }
    public int getMinimumSources() { return minimumSources; }
    public int getMinimumDistinctHosts() { return minimumDistinctHosts; }
    public boolean isRecoverable() { return recoverable; }
    public Limitation getLimitation() { return limitation; }
    public RecommendedAction getRecommendedAction() { return recommendedAction; }
}

package com.aresstack.askai.research.domain.search;

/**
 * The decisions a search run is executed under, as ONE snapshot-able object instead of a handful of loose
 * integers threaded through the code. A run records the profile it ran under, so its numbers can be read
 * later with the reason behind them.
 * <p>
 * Two distinctions this contract exists to make explicit, both learned from real runs:
 * <ul>
 * <li><b>Attempts are not successful reads.</b> "Read 3 pages" must not degrade to "try 3 URLs" the moment
 *     two of them hit a consent wall — hence {@link #getMaxVisitAttempts()} AND
 *     {@link #getTargetSuccessfulVisits()}.</li>
 * <li><b>Link expansion is its own budget.</b> Visiting 3 search hits that each contribute 5 links would
 *     otherwise silently become 18 pages read, which is not what "visit at most three hits" means.</li>
 * </ul>
 * With {@link ResultAcquisition#SERP_ONLY} no result page is opened at all, which makes a
 * wait-for-the-user obstacle structurally impossible: a short orientation lookup can never end up asking the
 * user to operate a foreign website.
 */
public final class SearchStrategyProfile {

    /** Whether result pages are opened at all. */
    public enum ResultAcquisition { SERP_ONLY, VISIT_RESULTS }

    /** Collect everything first and then choose, or evaluate as you go. */
    public enum AcquisitionOrder {
        /**
         * Collect all batches into one pool, then dedupe/diversify/rank and select. Orientation needs this:
         * otherwise the first result page alone decides the map of the topic.
         */
        COLLECT_THEN_SELECT,
        /** Evaluate after each batch and stop as soon as the target is met — cheaper for targeted checks. */
        PROGRESSIVE
    }

    /** How the candidates to inspect are chosen. */
    public enum CandidateSelection {
        /** The best-ranked ones. */
        TOP_RANKED,
        /** Deliberately spread across domains/source types — usually better for a first overview. */
        DIVERSE_RELEVANT,
        /** Exactly what the user picked. */
        USER_SELECTED,
        /** Exactly what the agent asked for. */
        AGENT_SELECTED,
        /** Rank plus a diversity correction. */
        HYBRID
    }

    /** What to do when a page cannot be read right away. */
    public enum ObstaclePolicy {
        /** Move on. The only sane choice for a short orientation. */
        SKIP,
        /** Park it and retry within this run once the domain is unblocked. */
        DEFER,
        /** Ask the user to resolve it — only ever for a deliberate, long research run. */
        WAIT_FOR_USER
    }

    /** How far links found on a visited page may pull the run along. */
    public enum LinkExpansion {
        /** Only the selected search hits are read. */
        NONE,
        /** Follow a bounded number of promising links. */
        LIMITED,
        /** Follow links as long as the run's budgets allow. */
        DEEP
    }

    /** Which providers may be used. */
    public enum ProviderPolicy {
        /** One fixed engine, no fallback chain — predictable, and what an orientation scan wants. */
        DUCKDUCKGO_ONLY,
        /** The configured default chain (may try several engines/providers). */
        DEFAULT_CHAIN
    }

    private final String name;
    private final ResultAcquisition resultAcquisition;
    private final int maxDiscoveryBatches;
    private final AcquisitionOrder acquisitionOrder;
    private final CandidateSelection candidateSelection;
    private final int maxVisitAttempts;
    private final int targetSuccessfulVisits;
    private final ObstaclePolicy obstaclePolicy;
    private final LinkExpansion linkExpansion;
    private final int maxExpandedLinks;
    private final ProviderPolicy providerPolicy;

    public SearchStrategyProfile(String name, ResultAcquisition resultAcquisition, int maxDiscoveryBatches,
                                 AcquisitionOrder acquisitionOrder, CandidateSelection candidateSelection,
                                 int maxVisitAttempts, int targetSuccessfulVisits,
                                 ObstaclePolicy obstaclePolicy, LinkExpansion linkExpansion,
                                 int maxExpandedLinks, ProviderPolicy providerPolicy) {
        this.name = name == null || name.trim().isEmpty() ? "custom" : name.trim();
        this.resultAcquisition = resultAcquisition == null
                ? ResultAcquisition.VISIT_RESULTS : resultAcquisition;
        this.maxDiscoveryBatches = Math.max(1, maxDiscoveryBatches);
        this.acquisitionOrder = acquisitionOrder == null
                ? AcquisitionOrder.COLLECT_THEN_SELECT : acquisitionOrder;
        this.candidateSelection = candidateSelection == null
                ? CandidateSelection.TOP_RANKED : candidateSelection;
        this.obstaclePolicy = obstaclePolicy == null ? ObstaclePolicy.SKIP : obstaclePolicy;
        this.linkExpansion = linkExpansion == null ? LinkExpansion.NONE : linkExpansion;
        this.providerPolicy = providerPolicy == null ? ProviderPolicy.DEFAULT_CHAIN : providerPolicy;
        // SERP_ONLY means exactly that: no visit budget, no link expansion. Keeping stray numbers around
        // would invite a later code path to open "just one" page.
        boolean visits = this.resultAcquisition == ResultAcquisition.VISIT_RESULTS;
        this.targetSuccessfulVisits = visits ? Math.max(0, targetSuccessfulVisits) : 0;
        // Attempts must be able to exceed the target, otherwise two skipped pages already end the run.
        this.maxVisitAttempts = visits
                ? Math.max(this.targetSuccessfulVisits, Math.max(0, maxVisitAttempts)) : 0;
        this.maxExpandedLinks = visits && this.linkExpansion != LinkExpansion.NONE
                ? Math.max(0, maxExpandedLinks) : 0;
    }

    /** A short, printable orientation scan: one engine, a few batches, nothing opened. */
    public static SearchStrategyProfile orientationSerpScan() {
        return new SearchStrategyProfile("ORIENTATION_SERP_SCAN", ResultAcquisition.SERP_ONLY, 3,
                AcquisitionOrder.COLLECT_THEN_SELECT, CandidateSelection.DIVERSE_RELEVANT,
                0, 0, ObstaclePolicy.SKIP, LinkExpansion.NONE, 0, ProviderPolicy.DUCKDUCKGO_ONLY);
    }

    /** A little deeper: a handful of pages actually read, still no link chasing and never a user prompt. */
    public static SearchStrategyProfile quickOrientation() {
        return new SearchStrategyProfile("QUICK_ORIENTATION", ResultAcquisition.VISIT_RESULTS, 3,
                AcquisitionOrder.COLLECT_THEN_SELECT, CandidateSelection.DIVERSE_RELEVANT,
                8, 3, ObstaclePolicy.SKIP, LinkExpansion.NONE, 0, ProviderPolicy.DUCKDUCKGO_ONLY);
    }

    /** The ordinary research run: deferred obstacles and bounded link expansion. */
    public static SearchStrategyProfile standardResearch() {
        return new SearchStrategyProfile("STANDARD_RESEARCH", ResultAcquisition.VISIT_RESULTS, 2,
                AcquisitionOrder.COLLECT_THEN_SELECT, CandidateSelection.HYBRID,
                20, 8, ObstaclePolicy.DEFER, LinkExpansion.LIMITED, 10, ProviderPolicy.DEFAULT_CHAIN);
    }

    /** The deliberate long run — the ONLY profile that may ask the user to resolve an obstacle. */
    public static SearchStrategyProfile deepResearch() {
        return new SearchStrategyProfile("DEEP_RESEARCH", ResultAcquisition.VISIT_RESULTS, 4,
                AcquisitionOrder.COLLECT_THEN_SELECT, CandidateSelection.HYBRID,
                60, 25, ObstaclePolicy.WAIT_FOR_USER, LinkExpansion.DEEP, 60,
                ProviderPolicy.DEFAULT_CHAIN);
    }

    public String getName() {
        return name;
    }

    public ResultAcquisition getResultAcquisition() {
        return resultAcquisition;
    }

    /** How many result portions may be collected — the traversal depth. */
    public int getMaxDiscoveryBatches() {
        return maxDiscoveryBatches;
    }

    public AcquisitionOrder getAcquisitionOrder() {
        return acquisitionOrder;
    }

    public CandidateSelection getCandidateSelection() {
        return candidateSelection;
    }

    /** How many pages may be TRIED; always at least the target, so skips do not eat the budget. */
    public int getMaxVisitAttempts() {
        return maxVisitAttempts;
    }

    /** How many pages should actually be READ — the number the user means by "visit N pages". */
    public int getTargetSuccessfulVisits() {
        return targetSuccessfulVisits;
    }

    public ObstaclePolicy getObstaclePolicy() {
        return obstaclePolicy;
    }

    public LinkExpansion getLinkExpansion() {
        return linkExpansion;
    }

    /** How many discovered links may additionally be read; 0 with {@link LinkExpansion#NONE}. */
    public int getMaxExpandedLinks() {
        return maxExpandedLinks;
    }

    public ProviderPolicy getProviderPolicy() {
        return providerPolicy;
    }

    /** No result page is opened — a wait-for-user obstacle cannot arise. */
    public boolean isSerpOnly() {
        return resultAcquisition == ResultAcquisition.SERP_ONLY;
    }

    public boolean mayWaitForUser() {
        return obstaclePolicy == ObstaclePolicy.WAIT_FOR_USER && !isSerpOnly();
    }

    public String describe() {
        return name + " acquisition=" + resultAcquisition + " batches<=" + maxDiscoveryBatches
                + " order=" + acquisitionOrder + " selection=" + candidateSelection
                + " visits=" + targetSuccessfulVisits + "/" + maxVisitAttempts
                + " obstacles=" + obstaclePolicy + " links=" + linkExpansion + "(" + maxExpandedLinks + ")"
                + " providers=" + providerPolicy;
    }
}

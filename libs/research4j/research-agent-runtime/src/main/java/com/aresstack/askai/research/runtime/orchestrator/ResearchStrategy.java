package com.aresstack.askai.research.runtime.orchestrator;

/**
 * The cooperation strategies of the research agent (ADR 0001, 4.3). The names are taken from Solon's
 * TeamAgent protocols as CONCEPTS; Solon's engine is not used. All three are always in effect, each on its
 * own level, so this enumeration documents where each one lives rather than selecting between them.
 *
 * <p>Rejected and therefore absent: SWARM (contradicts user-owned control), CONTRACT_NET and MARKET_BASED
 * (presuppose competing resources; with one GPU there is nothing to auction), A2A hand-over (a hand-off
 * between roles the orchestrator controls anyway).</p>
 */
public enum ResearchStrategy {

    /**
     * The {@link ResearchOrchestrator} delegates work packages to worker roles and evaluates their results;
     * the default of the research phase. Implemented by {@link OrchestratedResearchRun}.
     */
    HIERARCHICAL,

    /**
     * A dependency chain INSIDE a work package (probe, then read, then verify): a task's {@code dependsOn}.
     * Never a global "one at a time" rule; the {@link TaskExecutor} runs every ready task up to the
     * capacities. Implemented by the executor's dependency graph.
     */
    SEQUENTIAL,

    /**
     * The project store as the shared workspace: sources, concept, outline, document, tasks, receipts.
     * Workers read references from it and write artifacts back; nothing is copied between roles. Implemented
     * by the {@link TaskBoard}.
     */
    BLACKBOARD
}

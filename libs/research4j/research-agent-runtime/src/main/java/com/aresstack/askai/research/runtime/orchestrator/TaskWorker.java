package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.runtime.role.ResearchRole;

/**
 * A worker role the orchestrator may delegate to (ADR 0001, 4.1). The worker is the deterministic code that
 * runs one task of its role: it reads the brief and the referenced project artifacts, does its work through
 * its own tools and model slot, and records artifacts and receipts through the {@link WorkerContext} as it
 * goes. It never changes the task's state itself and never a phase: the {@link TaskExecutor} owns the task's
 * lifecycle, the user and the host state machine own the phase.
 *
 * <p>Lane G adds the search planner, the source verifier and the writer as further workers.</p>
 */
public interface TaskWorker {

    /** The role this worker runs: id, slot, tools, output. */
    ResearchRole role();

    /**
     * Whether a task of this role needs the session's browser, a separate capacity of its own (ADR 4.5:
     * other resources are separate capacities; I/O-bound work does not consume model capacity).
     */
    boolean needsBrowser();

    /**
     * Executes one task. Never throws for an expected failure: a technical problem the executor may retry
     * is {@link WorkerResult#retryable}, a definite failure {@link WorkerResult#failed}.
     */
    WorkerResult execute(ResearchTask task, WorkerContext context);
}

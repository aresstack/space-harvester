package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;

import java.util.List;

/**
 * The BLACKBOARD of the orchestrated run (ADR 0001, 4.1 and 4.3): the project store's view the orchestrator
 * and the executor share. Tasks and the run record are persisted as they change, so an interrupted run
 * resumes from the store (ADR 4.4); nothing of the run lives only in memory.
 *
 * <p>The plugin implements it over the project folder (the task store of lane E plus one run file); tests and
 * a session without a project use {@link InMemoryTaskBoard}. Every method may throw {@link TaskBoardException}
 * when the store cannot be read or written; the run then stops visibly instead of continuing unrecorded.</p>
 */
public interface TaskBoard {

    /** Every persisted task of the project, oldest first. */
    List<ResearchTask> tasks();

    /** Persists one task (insert or replace by id). */
    void save(ResearchTask task);

    /** The project's current orchestrated run, or {@code null} when none was ever started. */
    OrchestratorRun currentRun();

    /** Persists the run record (replaces the current one). */
    void saveRun(OrchestratorRun run);

    /** The store could not be read or written. */
    final class TaskBoardException extends RuntimeException {
        public TaskBoardException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.team.MainModelChat;

import java.util.List;

/**
 * What the {@link TaskExecutor} hands a {@link TaskWorker} for one attempt. Everything a worker records goes
 * onto the task and the board immediately, so a failed or interrupted attempt keeps its partial results
 * (ADR 0001, 4.4).
 */
public interface WorkerContext {

    /** The task's search query (from the orchestrator's brief). */
    String query();

    /** The orchestrator's brief for the task, without the query line. */
    String brief();

    /**
     * The current state of the tasks this task depends on, in the order of its {@code dependsOn} (ADR 0001,
     * 4.3 BLACKBOARD: a worker reads what its upstream packages recorded, e.g. the verifier the sources a web
     * research package accepted). Read when called, so it is the persisted state at that moment. The default
     * (a context without a board) has none.
     */
    default List<ResearchTask> dependencies() {
        return java.util.Collections.emptyList();
    }

    /**
     * Every task of this task's run as persisted at the moment of the call (the run's blackboard view). The
     * default (a context without a board) has none.
     */
    default List<ResearchTask> runTasks() {
        return java.util.Collections.emptyList();
    }

    /**
     * The worker role's chat transport; its token usage is booked on this task's budget. Workers that call a
     * model call it only through here.
     */
    MainModelChat chat();

    /**
     * {@code lane} restricted to the worker role's tool catalog (permissions per role, ADR 4.2): a call
     * outside the catalog fails with a {@link ToolInvoker.ToolFailure} and never reaches the host.
     */
    ToolInvoker permitted(ToolInvoker lane);

    /** Records one artifact on the task (and persists it). */
    void artifact(TaskArtifact.Kind kind, String content, String projectRef);

    /** Records the receipt of one tool call on the task (and persists it). */
    void receipt(String toolName, boolean succeeded, String receipt);

    /** A short progress line for the user's technical log. */
    void log(String line);

    /** True once the user stopped the run or the session closes; a worker ends its attempt promptly. */
    boolean isCancelled();

    /**
     * True once the task's own budget (time or tokens) is spent; a worker ends its attempt with what it has.
     */
    boolean isBudgetExhausted();

    /**
     * The task's token account: model calls a worker makes outside {@link #chat()} (e.g. the search run's
     * layout repair) are metered onto it, so they count against the task and the run budget.
     */
    java.util.concurrent.atomic.AtomicLong tokens();
}

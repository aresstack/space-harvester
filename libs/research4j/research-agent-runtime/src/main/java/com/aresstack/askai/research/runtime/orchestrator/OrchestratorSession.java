package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The orchestrated run as one research session holds it: the components wired once (board, workers,
 * executor, orchestrator, meter) and the user's commands routed to the run. The output of the turn that
 * carries a command receives the run's messages; between turns nothing is emitted.
 */
public final class OrchestratorSession {

    /** Where the run's messages go during one turn. */
    public interface Output {
        void visible(String message);

        void log(String line);
    }

    private final OrchestratedResearchRun run;
    private final AtomicReference<Output> output = new AtomicReference<Output>();
    private final Supplier<ResearchOutline> outline;

    /**
     * @param board           the project's blackboard (the plugin's project store, or in memory)
     * @param settings        the run's limits
     * @param chats           role chats over the host's model port (booking tokens)
     * @param port            the host's model port for the capacity view, or null
     * @param workers         the worker roles the orchestrator may delegate to
     * @param minimumSources  the run rule (search-run settings)
     * @param minimumHosts    the run rule (search-run settings)
     * @param maxOutputTokens the output budget of one orchestrator decision (the agent's turn budget)
     * @param outline         reads the approved outline when a run starts
     */
    public OrchestratorSession(TaskBoard board, OrchestratorSettings settings, RoleChats chats, ModelPort port,
                               List<TaskWorker> workers, int minimumSources, int minimumHosts,
                               int maxOutputTokens, Supplier<ResearchOutline> outline) {
        this(board, settings, chats, port, 0, workers, minimumSources, minimumHosts, maxOutputTokens, outline);
    }

    /**
     * @param browserSlots how many browser-bound tasks may run at once: the capacity of the chat's worker user
     *                     contexts; {@code <= 0} = the browser-slot setting (workers share the chat browser)
     */
    public OrchestratorSession(TaskBoard board, OrchestratorSettings settings, RoleChats chats, ModelPort port,
                               int browserSlots, List<TaskWorker> workers, int minimumSources, int minimumHosts,
                               int maxOutputTokens, Supplier<ResearchOutline> outline) {
        this.outline = outline;
        final AtomicReference<OrchestratedResearchRun> self = new AtomicReference<OrchestratedResearchRun>();
        TaskExecutor executor = new TaskExecutor(board, workers, chats,
                ExecutorCapacity.fromPort(port, browserSlots > 0 ? browserSlots : settings.getBrowserSlots()),
                System::currentTimeMillis,
                new TaskExecutor.Listener() {
                    public void taskStarted(com.aresstack.askai.research.domain.task.ResearchTask task) {
                        log("task started: " + task.getTaskId() + " on " + task.getEndpoint());
                    }

                    public void taskFinished(com.aresstack.askai.research.domain.task.ResearchTask task,
                                             WorkerResult result) {
                        log("task " + task.getTaskId() + " -> " + task.getState().wireName() + ": " + result);
                    }

                    public void tokensSpent(long tokens) {
                        OrchestratedResearchRun current = self.get();
                        if (current != null) {
                            current.bookTaskTokens(tokens);
                        }
                    }

                    public void log(String line) {
                        OrchestratorSession.this.log(line);
                    }
                });
        AtomicLong orchestratorTokens = new AtomicLong();
        ResearchOrchestrator orchestrator = new ResearchOrchestrator(
                chats.chatFor(ResearchOrchestrator.ROLE, orchestratorTokens), settings.getDecisionRepairAttempts(),
                maxOutputTokens);
        this.run = new OrchestratedResearchRun(board, executor, orchestrator, orchestratorTokens,
                new EvidenceMeter(minimumSources, minimumHosts, settings.getMinSourcesPerSection()), settings,
                System::currentTimeMillis, new OrchestratedResearchRun.Listener() {
                    public void visible(String message) {
                        Output out = output.get();
                        if (out != null && message != null && !message.isEmpty()) {
                            out.visible(message);
                        }
                    }

                    public void log(String line) {
                        OrchestratorSession.this.log(line);
                    }
                });
        self.set(run);
    }

    /** The default worker set of lane F: the web research worker over the session's funnel. */
    public static List<TaskWorker> defaultWorkers(WebAcquisition acquisition, ToolInvoker research,
                                                  OrchestratorSettings settings) {
        List<TaskWorker> workers = new ArrayList<TaskWorker>();
        workers.add(new WebResearchWorker(acquisition, research, settings.getEvidenceChars()));
        return workers;
    }

    /**
     * The worker set of a session (lane G): the web research worker, plus the search planner, the source
     * verifier and the writer as far as {@code roles} switches them on. The orchestrator can delegate only to
     * the roles listed here.
     *
     * @param maxOutputTokens the agent's model-turn budget (the default output budget of a worker answer)
     */
    public static List<TaskWorker> workers(WebAcquisition acquisition, ToolInvoker research,
                                           OrchestratorSettings settings, WorkerRoleSettings roles,
                                           int maxOutputTokens) {
        List<TaskWorker> workers = defaultWorkers(acquisition, research, settings);
        if (roles.isSearchPlanner()) {
            workers.add(new SearchPlannerWorker(roles.getPlannerMaxQueries(), roles.getRepairAttempts(),
                    maxOutputTokens));
        }
        if (roles.isSourceVerifier()) {
            workers.add(new SourceVerifierWorker(research, roles.getVerifierMaxSources(), roles.getRepairAttempts(),
                    maxOutputTokens));
        }
        if (roles.isWriter()) {
            workers.add(new WriterWorker(research, roles.getWriterMaxSources(), roles.getRepairAttempts(),
                    roles.getWriterMaxOutputTokens() > 0 ? roles.getWriterMaxOutputTokens() : maxOutputTokens));
        }
        return workers;
    }

    public OrchestratorRun current() {
        return run.current();
    }

    /** A research question: plan it and wait for the user's approval. */
    public OrchestratorRun start(String question, Output out) {
        return with(out, () -> run.start(question, outline.get()));
    }

    /** Approve the plan, approve an overrun, or resume an interrupted run. */
    public OrchestratorRun approve(AtomicBoolean cancelled, Output out) {
        return with(out, () -> run.approve(cancelled));
    }

    public OrchestratorRun answer(String answer, AtomicBoolean cancelled, Output out) {
        return with(out, () -> run.answer(answer, cancelled));
    }

    /** Reject the plan or stop the run. */
    public OrchestratorRun stop(Output out) {
        return with(out, run::stop);
    }

    private OrchestratorRun with(Output out, Supplier<OrchestratorRun> action) {
        output.set(out);
        try {
            return action.get();
        } catch (TaskBoard.TaskBoardException storeBroken) {
            out.visible("The research run could not be saved: " + storeBroken.getMessage());
            return null;
        } finally {
            output.compareAndSet(out, null);
        }
    }

    private void log(String line) {
        Output out = output.get();
        if (out != null) {
            out.log("[orchestrator] " + line);
        } else {
            System.err.println("[orchestrator] " + line);
        }
    }
}

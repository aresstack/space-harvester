package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskReceipt;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.team.MainModelChat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The TASK EXECUTOR (ADR 0001, 4.2): deterministic code that owns the task graph of a run. The orchestrator
 * decides WHAT should happen; the executor decides WHETHER, WHEN and WITH WHICH RESOURCES it may happen.
 *
 * <ul>
 *   <li><b>Dependencies</b> (SEQUENTIAL inside a package chain): a task starts only when every dependency is
 *       {@code completed}; a dependency that ended otherwise cancels the dependent task.</li>
 *   <li><b>Permissions</b>: a task of a role without a registered worker is rejected before it runs; a
 *       worker reaches the host only through its role's tool catalog ({@link WorkerContext#permitted}).</li>
 *   <li><b>Resources</b>: every ready task runs at once up to the {@link ExecutorCapacity capacities}; ready
 *       tasks are dispatched grouped by model so a single GPU does not switch models needlessly.</li>
 *   <li><b>Budgets</b>: each task carries a time and token budget (settings); spending accumulates across
 *       attempts. The run's own budget is checked before each dispatch ({@code mayDispatch}).</li>
 *   <li><b>Retries and resume</b>: a retryable failure re-queues the task within its retry limit;
 *       {@link #resume} re-queues tasks an interruption left {@code working} and never re-runs a completed one.</li>
 * </ul>
 *
 * <p>Every state change goes through the domain's transition table and is persisted on the {@link TaskBoard}
 * before anything else happens.</p>
 */
public final class TaskExecutor {

    /** Progress callbacks (technical log, live accounting). */
    public interface Listener {
        void taskStarted(ResearchTask task);

        void taskFinished(ResearchTask task, WorkerResult result);

        /** Tokens a task's model calls used, as they are booked. */
        void tokensSpent(long tokens);

        void log(String line);
    }

    private static final long POLL_MILLIS = 200L;

    private final TaskBoard board;
    private final Map<String, TaskWorker> workers;
    private final RoleChats chats;
    private final ExecutorCapacity capacity;
    private final LongSupplier clock;
    private final Listener listener;
    private final Map<String, Semaphore> semaphores = new HashMap<String, Semaphore>();
    private final Object boardLock = new Object();

    public TaskExecutor(TaskBoard board, List<TaskWorker> workers, RoleChats chats, ExecutorCapacity capacity,
                        LongSupplier clock, Listener listener) {
        this.board = board;
        Map<String, TaskWorker> byRole = new LinkedHashMap<String, TaskWorker>();
        for (TaskWorker worker : workers) {
            byRole.put(worker.role().getId(), worker);
        }
        this.workers = Collections.unmodifiableMap(byRole);
        this.chats = chats;
        this.capacity = capacity;
        this.clock = clock;
        this.listener = listener;
    }

    /** The role ids the executor can run: what the orchestrator may delegate to. */
    public Set<String> roles() {
        return workers.keySet();
    }

    public TaskWorker workerOf(String role) {
        return workers.get(role);
    }

    // ---- submission --------------------------------------------------------------------------------------

    /**
     * Persists planned packages as {@code submitted} tasks of {@code run}. A package of a role without a
     * worker is persisted and immediately {@code rejected}, so the refusal is on record.
     */
    public List<ResearchTask> submit(OrchestratorRun run, List<PlannedTask> planned, OrchestratorSettings settings) {
        List<ResearchTask> submitted = new ArrayList<ResearchTask>();
        long now = clock.getAsLong();
        for (PlannedTask package_ : planned) {
            List<String> dependsOn = new ArrayList<String>();
            for (String dependency : package_.getDependsOn()) {
                dependsOn.add(run.taskIdOf(dependency));
            }
            TaskWorker worker = workers.get(package_.getRole());
            String slot = worker == null ? "" : worker.role().getSlot().getName();
            ResearchTask task = ResearchTask.submit(run.taskIdOf(package_.getId()), "research", package_.getRole(),
                    dependsOn, TaskBriefs.inputsOf(package_), slot, 0,
                    TaskBudget.of(settings.getTaskTimeMinutes() * 60_000L, settings.getTaskTokenLimit()),
                    settings.getTaskRetryLimit(), now);
            if (worker == null) {
                task = task.reject("no worker for role " + package_.getRole(), now);
            }
            save(task);
            submitted.add(task);
        }
        return submitted;
    }

    // ---- resume ------------------------------------------------------------------------------------------

    /**
     * Resume after an interruption (ADR 0001, 4.4): a task of {@code run} found {@code working} is re-queued
     * within its retry limit, or failed when the limit is spent. {@code completed} tasks stay as they are,
     * {@code input-required} tasks keep waiting for the user. Returns how many tasks were re-queued.
     */
    public int resume(OrchestratorRun run) {
        int requeued = 0;
        long now = clock.getAsLong();
        for (ResearchTask task : board.tasks()) {
            if (!run.owns(task.getTaskId()) || task.getState() != TaskState.WORKING) {
                continue;
            }
            long at = Math.max(now, task.getUpdatedAtMillis());
            if (task.hasRetriesLeft()) {
                save(task.requeue("interrupted", at));
                requeued++;
            } else {
                save(task.fail("interrupted; retry limit spent", at));
            }
        }
        return requeued;
    }

    /** Cancels every open task of the run (the user stopped it). */
    public void cancelOpen(OrchestratorRun run, String reason) {
        long now = clock.getAsLong();
        for (ResearchTask task : board.tasks()) {
            if (run.owns(task.getTaskId()) && !task.isTerminal()) {
                save(task.cancel(reason, Math.max(now, task.getUpdatedAtMillis())));
            }
        }
    }

    /** Answers a task waiting for the user: back to the queue with the answer on record. */
    public boolean answer(OrchestratorRun run, String answer) {
        boolean any = false;
        long now = clock.getAsLong();
        for (ResearchTask task : board.tasks()) {
            if (run.owns(task.getTaskId()) && task.getState() == TaskState.INPUT_REQUIRED) {
                save(task.inputProvided(answer, Math.max(now, task.getUpdatedAtMillis())));
                any = true;
            }
        }
        return any;
    }

    // ---- execution ---------------------------------------------------------------------------------------

    /** Why {@link #runReady} returned. */
    public enum Stop {
        /** Nothing is ready or running anymore. */
        IDLE,
        /** The run's budget gate refused further dispatches; running tasks were finished. */
        BUDGET,
        /** Cancelled; running attempts were told to stop and were awaited. */
        CANCELLED
    }

    /**
     * Runs every ready task of {@code run}, up to the capacities, until nothing is ready or running. New
     * tasks are dispatched only while {@code mayDispatch} holds (the run budget); running tasks are always
     * awaited, so the method never leaves an attempt behind.
     */
    public Stop runReady(final OrchestratorRun run, final AtomicBoolean cancelled, BooleanSupplier mayDispatch) {
        final LinkedBlockingQueue<String> finished = new LinkedBlockingQueue<String>();
        final Set<String> running = new HashSet<String>();
        ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "research-task-" + run.getRunId());
                thread.setDaemon(true);
                return thread;
            }
        });
        Stop stop = Stop.IDLE;
        try {
            while (true) {
                drain(finished, running);
                if (cancelled.get()) {
                    stop = Stop.CANCELLED;
                }
                boolean dispatching = stop == Stop.IDLE && mayDispatch.getAsBoolean();
                if (stop == Stop.IDLE && !dispatching) {
                    stop = Stop.BUDGET;
                }
                if (dispatching) {
                    for (final ResearchTask task : readyTasks(run, running)) {
                        final TaskWorker worker = workers.get(task.getRole());
                        final List<ExecutorCapacity.Resource> resources = capacity.resourcesOf(worker);
                        if (!tryAcquire(resources)) {
                            continue;
                        }
                        final ResearchTask started;
                        try {
                            started = task.start(capacity.endpointOf(worker),
                                    Math.max(clock.getAsLong(), task.getUpdatedAtMillis()));
                            save(started);
                        } catch (RuntimeException failed) {
                            release(resources);
                            throw failed;
                        }
                        running.add(task.getTaskId());
                        listener.taskStarted(started);
                        pool.execute(new Runnable() {
                            public void run() {
                                try {
                                    attempt(run, started, worker, cancelled);
                                } finally {
                                    release(resources);
                                    finished.add(started.getTaskId());
                                }
                            }
                        });
                    }
                }
                if (running.isEmpty()) {
                    if (!dispatching || readyTasks(run, running).isEmpty()) {
                        return stop;
                    }
                    continue; // capacity freed meanwhile; dispatch again
                }
                try {
                    String done = finished.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (done != null) {
                        running.remove(done);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancelled.set(true);
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private void drain(LinkedBlockingQueue<String> finished, Set<String> running) {
        String done;
        while ((done = finished.poll()) != null) {
            running.remove(done);
        }
    }

    /**
     * The run's submitted tasks whose dependencies are completed, grouped by model (then oldest first). A
     * task whose dependency ended without completing is canceled here: it can never become ready.
     */
    List<ResearchTask> readyTasks(OrchestratorRun run, Set<String> running) {
        List<ResearchTask> all = board.tasks();
        Map<String, TaskState> states = new HashMap<String, TaskState>();
        for (ResearchTask task : all) {
            states.put(task.getTaskId(), task.getState());
        }
        List<ResearchTask> ready = new ArrayList<ResearchTask>();
        for (ResearchTask task : all) {
            if (!run.owns(task.getTaskId()) || task.getState() != TaskState.SUBMITTED
                    || running.contains(task.getTaskId())) {
                continue;
            }
            String broken = brokenDependency(task, states);
            if (broken != null) {
                save(task.cancel("dependency " + broken + " did not complete",
                        Math.max(clock.getAsLong(), task.getUpdatedAtMillis())));
                continue;
            }
            if (workers.get(task.getRole()) == null) {
                save(task.reject("no worker for role " + task.getRole(),
                        Math.max(clock.getAsLong(), task.getUpdatedAtMillis())));
                continue;
            }
            if (task.dependenciesSatisfiedBy(states)) {
                ready.add(task);
            }
        }
        final Map<String, String> modelKey = new HashMap<String, String>();
        for (ResearchTask task : ready) {
            String key = "";
            for (ExecutorCapacity.Resource resource : capacity.resourcesOf(workers.get(task.getRole()))) {
                if (resource.isModel()) {
                    key = resource.getKey();
                }
            }
            modelKey.put(task.getTaskId(), key);
        }
        Collections.sort(ready, new Comparator<ResearchTask>() {
            public int compare(ResearchTask a, ResearchTask b) {
                int byModel = modelKey.get(a.getTaskId()).compareTo(modelKey.get(b.getTaskId()));
                return byModel != 0 ? byModel : Long.compare(a.getCreatedAtMillis(), b.getCreatedAtMillis());
            }
        });
        return ready;
    }

    private static String brokenDependency(ResearchTask task, Map<String, TaskState> states) {
        for (String dependency : task.getDependsOn()) {
            TaskState state = states.get(dependency);
            if (state == null || (state.isTerminal() && state != TaskState.COMPLETED)) {
                return dependency;
            }
        }
        return null;
    }

    /** One attempt: run the worker, book the spending, map the result onto the state table, persist. */
    private void attempt(final OrchestratorRun run, ResearchTask started, TaskWorker worker,
                         AtomicBoolean cancelled) {
        final long begin = clock.getAsLong();
        final AtomicLong tokens = new AtomicLong();
        final TaskHolder holder = new TaskHolder(started);
        ResearchRole role = started.getContextSize() > 0
                ? worker.role().withContextTokens(started.getContextSize()) : worker.role();
        final MainModelChat chat = chats.chatFor(role, tokens);
        final ResearchTask initial = started;
        WorkerContext context = new WorkerContext() {
            public String query() {
                return TaskBriefs.queryOf(initial.getInputs());
            }

            public String brief() {
                return TaskBriefs.briefOf(initial.getInputs());
            }

            public List<ResearchTask> dependencies() {
                Map<String, ResearchTask> byId = new HashMap<String, ResearchTask>();
                for (ResearchTask task : snapshot()) {
                    byId.put(task.getTaskId(), task);
                }
                List<ResearchTask> dependencies = new ArrayList<ResearchTask>();
                for (String id : initial.getDependsOn()) {
                    ResearchTask dependency = byId.get(id);
                    if (dependency != null) {
                        dependencies.add(dependency);
                    }
                }
                return dependencies;
            }

            public List<ResearchTask> runTasks() {
                List<ResearchTask> tasks = new ArrayList<ResearchTask>();
                for (ResearchTask task : snapshot()) {
                    if (run.owns(task.getTaskId())) {
                        tasks.add(task);
                    }
                }
                return tasks;
            }

            public MainModelChat chat() {
                return chat;
            }

            public ToolInvoker permitted(ToolInvoker lane) {
                return new RoleScopedToolInvoker(lane, worker.role().getTools());
            }

            public void artifact(TaskArtifact.Kind kind, String content, String projectRef) {
                holder.update(new java.util.function.UnaryOperator<ResearchTask>() {
                    public ResearchTask apply(ResearchTask task) {
                        return task.withArtifact(new TaskArtifact(task.getTaskId() + ".a"
                                + (task.getArtifacts().size() + 1), kind, content, projectRef, clock.getAsLong()));
                    }
                });
            }

            public void receipt(String toolName, boolean succeeded, String receipt) {
                holder.update(new java.util.function.UnaryOperator<ResearchTask>() {
                    public ResearchTask apply(ResearchTask task) {
                        return task.withReceipt(new TaskReceipt(toolName, succeeded, receipt, clock.getAsLong()));
                    }
                });
            }

            public void log(String line) {
                listener.log(initial.getTaskId() + ": " + line);
            }

            public boolean isCancelled() {
                return cancelled.get();
            }

            public AtomicLong tokens() {
                return tokens;
            }

            public boolean isBudgetExhausted() {
                return initial.getBudget().spend(Math.max(0L, clock.getAsLong() - begin), tokens.get())
                        .isExhausted();
            }
        };
        WorkerResult result;
        try {
            result = worker.execute(started, context);
            if (result == null) {
                result = WorkerResult.failed("the worker returned no result");
            }
        } catch (RuntimeException crashed) {
            result = WorkerResult.retryable("worker error: " + crashed);
        }
        // The user interrupted the turn: the package stays WORKING with its spending booked, exactly like a
        // session that died mid-task, so the next approval resumes it within its retry budget (ADR 4.4). An
        // explicit stop cancels it through cancelOpen instead.
        final boolean interrupted = cancelled.get() && result.getKind() != WorkerResult.Kind.COMPLETED;
        final long spentMillis = Math.max(0L, clock.getAsLong() - begin);
        final long spentTokens = tokens.get();
        listener.tokensSpent(spentTokens);
        final WorkerResult outcome = result;
        ResearchTask after = holder.update(new java.util.function.UnaryOperator<ResearchTask>() {
            public ResearchTask apply(ResearchTask task) {
                ResearchTask spent = task.withSpending(spentMillis, spentTokens);
                long at = Math.max(clock.getAsLong(), spent.getUpdatedAtMillis());
                if (interrupted) {
                    return spent;
                }
                switch (outcome.getKind()) {
                    case COMPLETED:
                        return spent.complete(at);
                    case NEEDS_INPUT:
                        return spent.requireInput(outcome.getDetail(), at);
                    case CANCELED:
                        return spent.cancel(outcome.getDetail(), at);
                    case RETRYABLE:
                        if (spent.hasRetriesLeft() && !spent.getBudget().isExhausted()) {
                            return spent.requeue(outcome.getDetail(), at);
                        }
                        return spent.fail(outcome.getDetail() + (spent.getBudget().isExhausted()
                                ? " (task budget spent)" : " (retry limit spent)"), at);
                    default:
                        return spent.fail(outcome.getDetail(), at);
                }
            }
        });
        listener.taskFinished(after, outcome);
    }

    private boolean tryAcquire(List<ExecutorCapacity.Resource> resources) {
        List<Semaphore> taken = new ArrayList<Semaphore>();
        for (ExecutorCapacity.Resource resource : resources) {
            Semaphore semaphore = semaphoreOf(resource);
            if (!semaphore.tryAcquire()) {
                for (Semaphore held : taken) {
                    held.release();
                }
                return false;
            }
            taken.add(semaphore);
        }
        return true;
    }

    private void release(List<ExecutorCapacity.Resource> resources) {
        for (ExecutorCapacity.Resource resource : resources) {
            semaphoreOf(resource).release();
        }
    }

    private Semaphore semaphoreOf(ExecutorCapacity.Resource resource) {
        synchronized (semaphores) {
            Semaphore semaphore = semaphores.get(resource.getKey());
            if (semaphore == null) {
                semaphore = new Semaphore(resource.getCapacity());
                semaphores.put(resource.getKey(), semaphore);
            }
            return semaphore;
        }
    }

    private void save(ResearchTask task) {
        synchronized (boardLock) {
            board.save(task);
        }
    }

    private List<ResearchTask> snapshot() {
        synchronized (boardLock) {
            return board.tasks();
        }
    }

    /** The live task of one attempt: every recording is applied and persisted under one lock. */
    private final class TaskHolder {
        private ResearchTask task;

        TaskHolder(ResearchTask task) {
            this.task = task;
        }

        synchronized ResearchTask update(java.util.function.UnaryOperator<ResearchTask> change) {
            // A stop cancels the package on the board while the worker still runs: the board's final word stands.
            for (ResearchTask stored : board.tasks()) {
                if (stored.getTaskId().equals(task.getTaskId()) && stored.isTerminal() && !task.isTerminal()) {
                    task = stored;
                    return stored;
                }
            }
            if (task.isTerminal()) {
                return task;
            }
            ResearchTask next = change.apply(task);
            save(next);
            task = next;
            return next;
        }
    }
}

package com.aresstack.askai.research.domain.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One work package of a research run (ADR 0001, section 4.4): what the orchestrator decided should happen,
 * as an explicit, persistable object the deterministic task executor schedules, retries and resumes.
 * <p>
 * Immutable: every transition returns a NEW task and appends to {@link #getTransitions()}, so the history is
 * never rewritten. Every state change goes through the {@link TaskState} table; anything else throws
 * {@link IllegalTaskTransitionException}. The domain owns no clock — all times are passed in by the caller.
 * <p>
 * Resume semantics (enforced by the executor on top of these rules): {@code COMPLETED} is terminal and never
 * re-executed; a task found {@code WORKING} after an interruption is {@link #requeue re-queued}, which counts
 * a retry and is refused once the retry limit is spent; {@code INPUT_REQUIRED} waits for the user;
 * {@link #getBudget() budgets} keep accumulating across the interruption.
 * <p>
 * {@code phase} is the NAME of the research phase the task belongs to (the plugin's {@code ResearchPhase}
 * constant). The phase enum and its state machine live in the plugin, which is the sole transition
 * authority; this domain module must not depend on it, and a task never changes a phase.
 */
public final class ResearchTask {

    private final String taskId;
    private final String phase;
    private final String role;
    private final List<String> dependsOn;
    private final TaskState state;
    private final TaskInputs inputs;
    private final List<TaskArtifact> artifacts;
    private final String modelSlot;
    private final int contextSize;
    private final TaskBudget budget;
    private final int retryCount;
    private final int retryLimit;
    private final List<TaskReceipt> receipts;
    private final List<TaskTransition> transitions;
    private final String endpoint;

    private ResearchTask(String taskId, String phase, String role, List<String> dependsOn, TaskState state,
                         TaskInputs inputs, List<TaskArtifact> artifacts, String modelSlot, int contextSize,
                         TaskBudget budget, int retryCount, int retryLimit, List<TaskReceipt> receipts,
                         List<TaskTransition> transitions, String endpoint) {
        this.taskId = requireText(taskId, "taskId");
        this.phase = requireText(phase, "phase");
        this.role = requireText(role, "role");
        this.dependsOn = dependencies(this.taskId, dependsOn);
        if (state == null) {
            throw new IllegalArgumentException("task " + this.taskId + ": state is required");
        }
        this.state = state;
        this.inputs = inputs == null ? TaskInputs.empty() : inputs;
        this.artifacts = copy(artifacts);
        this.modelSlot = modelSlot == null ? "" : modelSlot.trim();
        if (contextSize < 0) {
            throw new IllegalArgumentException("task " + this.taskId + ": contextSize must not be negative");
        }
        this.contextSize = contextSize;
        this.budget = budget == null ? TaskBudget.unlimited() : budget;
        if (retryLimit < 0 || retryCount < 0) {
            throw new IllegalArgumentException("task " + this.taskId + ": retry values must not be negative");
        }
        if (retryCount > retryLimit) {
            throw new IllegalArgumentException("task " + this.taskId + ": retryCount " + retryCount
                    + " exceeds retryLimit " + retryLimit);
        }
        this.retryCount = retryCount;
        this.retryLimit = retryLimit;
        this.receipts = copy(receipts);
        this.transitions = copy(transitions);
        if (this.transitions.isEmpty() || this.transitions.get(this.transitions.size() - 1).getTo() != state) {
            throw new IllegalArgumentException("task " + this.taskId
                    + ": the transition history must end in the current state " + state);
        }
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    /**
     * A new task in {@code SUBMITTED}, as the orchestrator hands it to the executor.
     *
     * @param modelSlot   the role-level model slot name (e.g. {@code judge}, {@code synthesis}) the platform
     *                    resolves to a model on an endpoint; empty means the role's default slot
     * @param contextSize context window for this task's model calls in tokens; {@code 0} means the slot default
     * @param retryLimit  how often the task may be re-queued after an interrupted or failed attempt (a setting)
     */
    public static ResearchTask submit(String taskId, String phase, String role, List<String> dependsOn,
                                      TaskInputs inputs, String modelSlot, int contextSize, TaskBudget budget,
                                      int retryLimit, long createdAtMillis) {
        return new ResearchTask(taskId, phase, role, dependsOn, TaskState.SUBMITTED, inputs, null, modelSlot,
                contextSize, budget, 0, retryLimit, null,
                Collections.singletonList(new TaskTransition(null, TaskState.SUBMITTED, createdAtMillis, "")),
                "");
    }

    /**
     * Rebuilds a persisted task. The history must start with the creation entry and replay through the
     * {@link TaskState} table to {@code state}; a store that does not obey the rules is rejected on load
     * instead of being silently accepted.
     */
    public static ResearchTask restore(String taskId, String phase, String role, List<String> dependsOn,
                                       TaskState state, TaskInputs inputs, List<TaskArtifact> artifacts,
                                       String modelSlot, int contextSize, TaskBudget budget, int retryCount,
                                       int retryLimit, List<TaskReceipt> receipts,
                                       List<TaskTransition> transitions, String endpoint) {
        if (transitions == null || transitions.isEmpty() || transitions.get(0).getFrom() != null
                || transitions.get(0).getTo() != TaskState.SUBMITTED) {
            throw new IllegalArgumentException("task " + taskId + ": history must start with the submission");
        }
        int requeues = 0;
        for (int i = 1; i < transitions.size(); i++) {
            TaskTransition step = transitions.get(i);
            TaskState previous = transitions.get(i - 1).getTo();
            if (step.getFrom() != previous || !previous.canTransitionTo(step.getTo())) {
                throw new IllegalArgumentException("task " + taskId + ": history step " + i + " ("
                        + step.getFrom() + " -> " + step.getTo() + ") breaks the transition rules");
            }
            if (step.getFrom() == TaskState.WORKING && step.getTo() == TaskState.SUBMITTED) {
                requeues++;
            }
        }
        ResearchTask task = new ResearchTask(taskId, phase, role, dependsOn, state, inputs, artifacts, modelSlot,
                contextSize, budget, retryCount, retryLimit, receipts, transitions, endpoint);
        // The counter is derived evidence: a store that under-reports it would let a resume bypass the limit.
        if (requeues != task.retryCount) {
            throw new IllegalArgumentException("task " + taskId + ": retryCount " + retryCount
                    + " disagrees with the " + requeues + " re-queues in the history");
        }
        return task;
    }

    // ---- transitions -------------------------------------------------------------------------------------

    /** {@code SUBMITTED → WORKING} on the endpoint the resource scheduler reserved. */
    public ResearchTask start(String endpoint, long atMillis) {
        String assigned = endpoint == null ? "" : endpoint.trim();
        if (assigned.isEmpty()) {
            throw new IllegalTaskTransitionException(taskId, state, TaskState.WORKING,
                    "a task starts only on an assigned endpoint");
        }
        return to(TaskState.WORKING, atMillis, "", retryCount, assigned);
    }

    /** {@code WORKING → INPUT_REQUIRED}: the worker needs the user; {@code question} says what for. */
    public ResearchTask requireInput(String question, long atMillis) {
        return to(TaskState.INPUT_REQUIRED, atMillis, question, retryCount, endpoint);
    }

    /** {@code INPUT_REQUIRED → SUBMITTED}: the user answered; the task goes back to the queue. */
    public ResearchTask inputProvided(String answer, long atMillis) {
        if (state != TaskState.INPUT_REQUIRED) {
            throw new IllegalTaskTransitionException(taskId, state, TaskState.SUBMITTED,
                    "only a task waiting for input can receive it");
        }
        return to(TaskState.SUBMITTED, atMillis, answer, retryCount, endpoint);
    }

    /**
     * {@code WORKING → SUBMITTED} for another attempt (interrupted run, retryable error). Counts a retry and
     * is refused once {@link #getRetryLimit()} is spent — the executor then {@link #fail fails} the task.
     */
    public ResearchTask requeue(String reason, long atMillis) {
        if (state != TaskState.WORKING) {
            throw new IllegalTaskTransitionException(taskId, state, TaskState.SUBMITTED,
                    "only a working task can be re-queued");
        }
        if (!hasRetriesLeft()) {
            throw new IllegalTaskTransitionException(taskId, state, TaskState.SUBMITTED,
                    "retry limit " + retryLimit + " spent");
        }
        return to(TaskState.SUBMITTED, atMillis, reason, retryCount + 1, endpoint);
    }

    /** {@code WORKING → COMPLETED}. Final: the task is never executed again. */
    public ResearchTask complete(long atMillis) {
        return to(TaskState.COMPLETED, atMillis, "", retryCount, endpoint);
    }

    /** {@code WORKING | INPUT_REQUIRED → FAILED}. */
    public ResearchTask fail(String reason, long atMillis) {
        return to(TaskState.FAILED, atMillis, reason, retryCount, endpoint);
    }

    /** Any non-terminal state {@code → CANCELED}. */
    public ResearchTask cancel(String reason, long atMillis) {
        return to(TaskState.CANCELED, atMillis, reason, retryCount, endpoint);
    }

    /** {@code SUBMITTED → REJECTED}: refused before it ran (permissions, scope, budget). */
    public ResearchTask reject(String reason, long atMillis) {
        return to(TaskState.REJECTED, atMillis, reason, retryCount, endpoint);
    }

    // ---- recording while the task is open ---------------------------------------------------------------

    /** The task with one more output; refused once the task is terminal. */
    public ResearchTask withArtifact(TaskArtifact artifact) {
        requireOpen("record an artifact");
        if (artifact == null) {
            return this;
        }
        List<TaskArtifact> extended = new ArrayList<TaskArtifact>(artifacts);
        extended.add(artifact);
        return copyWith(extended, receipts, budget);
    }

    /** The task with one more tool-call receipt; refused once the task is terminal. */
    public ResearchTask withReceipt(TaskReceipt receipt) {
        requireOpen("record a receipt");
        if (receipt == null) {
            return this;
        }
        List<TaskReceipt> extended = new ArrayList<TaskReceipt>(receipts);
        extended.add(receipt);
        return copyWith(artifacts, extended, budget);
    }

    /** The task with spending added to its budget account; refused once the task is terminal. */
    public ResearchTask withSpending(long millis, long tokens) {
        requireOpen("record spending");
        return copyWith(artifacts, receipts, budget.spend(millis, tokens));
    }

    // ---- queries -----------------------------------------------------------------------------------------

    /** Whether every dependency is {@code COMPLETED} in the given view of the task graph (unknown = not). */
    public boolean dependenciesSatisfiedBy(Map<String, TaskState> statesByTaskId) {
        for (String dependency : dependsOn) {
            if (statesByTaskId == null || statesByTaskId.get(dependency) != TaskState.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public boolean hasRetriesLeft() {
        return retryCount < retryLimit;
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public String getTaskId() {
        return taskId;
    }

    /** The research phase name this task belongs to (see the class comment). */
    public String getPhase() {
        return phase;
    }

    /** The worker role that executes it, e.g. {@code source-verifier}. */
    public String getRole() {
        return role;
    }

    /** Ids of tasks that must be {@code COMPLETED} before this one may start. */
    public List<String> getDependsOn() {
        return dependsOn;
    }

    public TaskState getState() {
        return state;
    }

    public TaskInputs getInputs() {
        return inputs;
    }

    public List<TaskArtifact> getArtifacts() {
        return artifacts;
    }

    /** The model slot name; empty means the role's default slot. Never a model name. */
    public String getModelSlot() {
        return modelSlot;
    }

    /** Context size in tokens for this task's model calls; {@code 0} means the slot default. */
    public int getContextSize() {
        return contextSize;
    }

    public TaskBudget getBudget() {
        return budget;
    }

    /** How many times the task has been re-queued so far. */
    public int getRetryCount() {
        return retryCount;
    }

    public int getRetryLimit() {
        return retryLimit;
    }

    public List<TaskReceipt> getReceipts() {
        return receipts;
    }

    /** The full state history, oldest first; the first entry is the submission. */
    public List<TaskTransition> getTransitions() {
        return transitions;
    }

    /** The endpoint of the latest attempt, or empty when the task never started. */
    public String getEndpoint() {
        return endpoint;
    }

    public long getCreatedAtMillis() {
        return transitions.get(0).getAtMillis();
    }

    public long getUpdatedAtMillis() {
        return transitions.get(transitions.size() - 1).getAtMillis();
    }

    /** When the first attempt started, or {@code 0} when the task never ran. */
    public long getStartedAtMillis() {
        for (TaskTransition transition : transitions) {
            if (transition.getTo() == TaskState.WORKING) {
                return transition.getAtMillis();
            }
        }
        return 0L;
    }

    /** When the task reached its terminal state, or {@code 0} while it is open. */
    public long getFinishedAtMillis() {
        return isTerminal() ? getUpdatedAtMillis() : 0L;
    }

    // ---- internals ---------------------------------------------------------------------------------------

    private ResearchTask to(TaskState target, long atMillis, String reason, int newRetryCount,
                            String newEndpoint) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalTaskTransitionException(taskId, state, target,
                    state.isTerminal() ? state + " is terminal" : "not in the transition table");
        }
        if (atMillis < getUpdatedAtMillis()) {
            throw new IllegalTaskTransitionException(taskId, state, target,
                    "time runs backwards (" + atMillis + " < " + getUpdatedAtMillis() + ")");
        }
        List<TaskTransition> extended = new ArrayList<TaskTransition>(transitions);
        extended.add(new TaskTransition(state, target, atMillis, reason));
        return new ResearchTask(taskId, phase, role, dependsOn, target, inputs, artifacts, modelSlot,
                contextSize, budget, newRetryCount, retryLimit, receipts, extended, newEndpoint);
    }

    private ResearchTask copyWith(List<TaskArtifact> newArtifacts, List<TaskReceipt> newReceipts,
                                  TaskBudget newBudget) {
        return new ResearchTask(taskId, phase, role, dependsOn, state, inputs, newArtifacts, modelSlot,
                contextSize, newBudget, retryCount, retryLimit, newReceipts, transitions, endpoint);
    }

    private void requireOpen(String what) {
        if (isTerminal()) {
            throw new IllegalStateException("task " + taskId + " is " + state + ": cannot " + what);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value.trim();
    }

    private static List<String> dependencies(String taskId, List<String> dependsOn) {
        List<String> ids = new ArrayList<String>();
        if (dependsOn != null) {
            for (String id : dependsOn) {
                if (id == null || id.trim().isEmpty()) {
                    continue;
                }
                if (id.trim().equals(taskId)) {
                    throw new IllegalArgumentException("task " + taskId + " must not depend on itself");
                }
                if (!ids.contains(id.trim())) {
                    ids.add(id.trim());
                }
            }
        }
        return Collections.unmodifiableList(ids);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }
}

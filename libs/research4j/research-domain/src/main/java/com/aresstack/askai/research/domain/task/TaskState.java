package com.aresstack.askai.research.domain.task;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The state of one work package (ADR 0001, section 4.4). This is OUR enumeration, owned by the research
 * domain; the names match A2A 1.0 by choice so that a later A2A adapter is a mapping, never a dependency.
 * <p>
 * The allowed transitions are a closed table — {@link #canTransitionTo(TaskState)} is the single rule the
 * {@link ResearchTask} transition methods consult, so an executor, a resume pass or a UI cannot invent a
 * transition of its own:
 * <pre>
 * SUBMITTED      → WORKING | CANCELED | REJECTED
 * WORKING        → SUBMITTED (re-queue, counts a retry) | INPUT_REQUIRED | COMPLETED | FAILED | CANCELED
 * INPUT_REQUIRED → SUBMITTED (input given, back to the queue) | FAILED | CANCELED
 * COMPLETED, FAILED, CANCELED, REJECTED → nothing (terminal)
 * </pre>
 * A retry is NOT a way out of {@code FAILED}: the executor re-queues a working task while its retry limit
 * allows and fails it only when the limit is spent or the error is not retryable. Terminal states are final,
 * so a completed task is never executed again.
 */
public enum TaskState {
    /** Accepted into the task graph, waiting for its dependencies and a resource slot. */
    SUBMITTED("submitted"),
    /** A worker is executing it on an assigned endpoint. */
    WORKING("working"),
    /** Paused until the USER answers; never answered by the orchestrator or the executor. */
    INPUT_REQUIRED("input-required"),
    /** Done; its artifacts are the result. Never re-executed. */
    COMPLETED("completed"),
    /** Gave up: retry limit spent or a non-retryable error. */
    FAILED("failed"),
    /** Stopped by the user or by the run (budget gate, re-plan) before it finished. */
    CANCELED("canceled"),
    /** Refused before it ever ran: permissions, scope or budget did not admit it. */
    REJECTED("rejected");

    private final String wireName;

    TaskState(String wireName) {
        this.wireName = wireName;
    }

    /** The stable lower-case name used in persisted stores and in schema output, e.g. {@code input-required}. */
    public String wireName() {
        return wireName;
    }

    /** Parses a {@link #wireName()} (or the constant name) back; unknown values are rejected, never guessed. */
    public static TaskState fromWireName(String value) {
        if (value != null) {
            String trimmed = value.trim();
            for (TaskState state : values()) {
                if (state.wireName.equalsIgnoreCase(trimmed) || state.name().equalsIgnoreCase(trimmed)) {
                    return state;
                }
            }
        }
        throw new IllegalArgumentException("unknown task state: " + value);
    }

    /** No transition leaves a terminal state. */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }

    /** The states this one may move to; empty for terminal states. */
    public Set<TaskState> allowedTargets() {
        switch (this) {
            case SUBMITTED:
                return Collections.unmodifiableSet(EnumSet.of(WORKING, CANCELED, REJECTED));
            case WORKING:
                return Collections.unmodifiableSet(
                        EnumSet.of(SUBMITTED, INPUT_REQUIRED, COMPLETED, FAILED, CANCELED));
            case INPUT_REQUIRED:
                return Collections.unmodifiableSet(EnumSet.of(SUBMITTED, FAILED, CANCELED));
            default:
                return Collections.unmodifiableSet(EnumSet.noneOf(TaskState.class));
        }
    }

    public boolean canTransitionTo(TaskState target) {
        return target != null && allowedTargets().contains(target);
    }
}

package com.aresstack.askai.research.domain.task;

/** One recorded state change of a task: from, to, when (caller's clock) and why. The history is never rewritten. */
public final class TaskTransition {

    private final TaskState from;
    private final TaskState to;
    private final long atMillis;
    private final String reason;

    public TaskTransition(TaskState from, TaskState to, long atMillis, String reason) {
        if (to == null) {
            throw new IllegalArgumentException("target state is required");
        }
        this.from = from;
        this.to = to;
        this.atMillis = atMillis;
        this.reason = reason == null ? "" : reason.trim();
    }

    /** {@code null} for the creation entry of a task. */
    public TaskState getFrom() {
        return from;
    }

    public TaskState getTo() {
        return to;
    }

    public long getAtMillis() {
        return atMillis;
    }

    public String getReason() {
        return reason;
    }
}

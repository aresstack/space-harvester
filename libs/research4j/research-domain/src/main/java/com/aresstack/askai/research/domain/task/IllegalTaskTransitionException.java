package com.aresstack.askai.research.domain.task;

/** A transition the {@link TaskState} table (or a guard such as the retry limit) does not admit. */
public final class IllegalTaskTransitionException extends IllegalStateException {

    private final String taskId;
    private final TaskState from;
    private final TaskState to;

    public IllegalTaskTransitionException(String taskId, TaskState from, TaskState to, String why) {
        super("task " + taskId + ": " + from + " -> " + to + " rejected: " + why);
        this.taskId = taskId;
        this.from = from;
        this.to = to;
    }

    public String getTaskId() {
        return taskId;
    }

    public TaskState getFrom() {
        return from;
    }

    public TaskState getTo() {
        return to;
    }
}

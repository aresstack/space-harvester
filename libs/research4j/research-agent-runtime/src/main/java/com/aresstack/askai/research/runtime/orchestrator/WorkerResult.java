package com.aresstack.askai.research.runtime.orchestrator;

/** How a {@link TaskWorker} ended one attempt; the executor maps it onto the task's state table. */
public final class WorkerResult {

    public enum Kind {
        /** The work is done; the recorded artifacts are the result. */
        COMPLETED,
        /** A technical problem; another attempt may succeed (within the task's retry limit). */
        RETRYABLE,
        /** A definite failure; retrying would not help. */
        FAILED,
        /** Only the user can answer what the worker needs. */
        NEEDS_INPUT,
        /** The attempt was cancelled (user stop, session close). */
        CANCELED
    }

    private final Kind kind;
    private final String detail;

    private WorkerResult(Kind kind, String detail) {
        this.kind = kind;
        this.detail = detail == null ? "" : detail.trim();
    }

    public static WorkerResult completed(String summary) {
        return new WorkerResult(Kind.COMPLETED, summary);
    }

    public static WorkerResult retryable(String reason) {
        return new WorkerResult(Kind.RETRYABLE, reason);
    }

    public static WorkerResult failed(String reason) {
        return new WorkerResult(Kind.FAILED, reason);
    }

    public static WorkerResult needsInput(String question) {
        return new WorkerResult(Kind.NEEDS_INPUT, question);
    }

    public static WorkerResult canceled(String reason) {
        return new WorkerResult(Kind.CANCELED, reason);
    }

    public Kind getKind() {
        return kind;
    }

    /** Summary, reason or question, in one English line. */
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return kind + (detail.isEmpty() ? "" : ": " + detail);
    }
}

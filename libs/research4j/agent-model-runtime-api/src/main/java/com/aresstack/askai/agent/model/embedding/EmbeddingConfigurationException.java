package com.aresstack.askai.agent.model.embedding;

/**
 * Thrown when a session's embedding endpoint cannot be prepared: no usable embedding model is selected, its
 * runtime cannot be started, or the snapshot cannot be written. Never a silent "first found" fallback — the
 * caller may treat it as "no embedding descriptor" and degrade honestly.
 */
public final class EmbeddingConfigurationException extends Exception {

    /** The typed cause — a code, not a new exception hierarchy, so callers can react precisely. */
    public enum Reason {
        MODEL_NOT_CONFIGURED,
        MODEL_NOT_FOUND,
        MODEL_NOT_EMBEDDING_CAPABLE,
        MODEL_NOT_RUNNABLE,
        RUNTIME_START_FAILED,
        DIMENSION_PROBE_FAILED,
        INVALID_PROBE_RESPONSE,
        MISSING_MODEL_REVISION
    }

    private final Reason reason;

    public EmbeddingConfigurationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EmbeddingConfigurationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

package com.aresstack.askai.agent.model.nlp;

/**
 * Thrown when a session's NLP model cannot be resolved. A typed {@link Reason} lets the caller degrade
 * correctly: {@code MODEL_NOT_CONFIGURED} / {@code MODEL_NOT_INSTALLED} are EXPECTED states that permit the
 * deterministic regex fallback, whereas {@code ARTIFACT_MISSING} / {@code CHECKSUM_MISMATCH} mean a selected
 * model is broken/tampered and must surface rather than silently degrade.
 */
public final class NlpConfigurationException extends Exception {

    public enum Reason {
        MODEL_NOT_CONFIGURED,
        MODEL_NOT_INSTALLED,
        UNSUPPORTED_CAPABILITY,
        ARTIFACT_MISSING,
        CHECKSUM_MISMATCH
    }

    private final Reason reason;

    public NlpConfigurationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public NlpConfigurationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    /** True when this reason is an expected "no model" state that allows the regex fallback (not a corruption). */
    public boolean allowsRegexFallback() {
        return reason == Reason.MODEL_NOT_CONFIGURED || reason == Reason.MODEL_NOT_INSTALLED
                || reason == Reason.UNSUPPORTED_CAPABILITY;
    }
}

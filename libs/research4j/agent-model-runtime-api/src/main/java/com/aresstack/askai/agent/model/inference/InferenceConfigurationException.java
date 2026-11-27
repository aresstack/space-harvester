package com.aresstack.askai.agent.model.inference;

/**
 * A visible failure while preparing the structured-inference configuration for a session: the central main
 * model is unset, cannot be resolved to a serving endpoint, or the snapshot could not be written. Unlike the
 * mandatory reranker, inference is OPTIONAL — the caller may choose to continue with the honest
 * unavailable-fallback — so this is a checked exception the caller decides how to treat.
 */
public final class InferenceConfigurationException extends Exception {

    public InferenceConfigurationException(String message) {
        super(message);
    }

    public InferenceConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

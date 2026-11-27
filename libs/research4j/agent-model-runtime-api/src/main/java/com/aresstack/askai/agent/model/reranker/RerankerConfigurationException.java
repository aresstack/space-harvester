package com.aresstack.askai.agent.model.reranker;

/**
 * A host could not prepare a usable reranker configuration snapshot for a session — no rerank-capable
 * local model is installed/selectable, the local runtime could not be started, or the snapshot could
 * not be written. It is a VISIBLE, terminal condition: the productive session start must fail with this
 * reason rather than silently continuing without the mandatory reranker.
 */
public final class RerankerConfigurationException extends Exception {

    public RerankerConfigurationException(String message) {
        super(message);
    }

    public RerankerConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

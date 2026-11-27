package com.aresstack.askai.agent.model.inference;

import java.io.File;

/**
 * The neutral host SPI that publishes the per-session structured-inference descriptor for the productive
 * research path. AskAI's implementation reads the central main model ({@code ai.mainModel}), resolves it to
 * its actual serving endpoint (local runtime sidecar or configured Ollama), and writes an atomic,
 * sekret-free {@code inference-config.json} into the session directory — handing the agent only the file
 * path. The agent never selects a model.
 *
 * <p>Unlike the mandatory reranker provider this is OPTIONAL: a missing or unresolvable main model yields an
 * {@link InferenceConfigurationException} the caller may treat as "no inference descriptor" (the agent then
 * keeps the honest unavailable-fallback for SERP layout repair) rather than failing the whole session.</p>
 */
public interface InferenceConfigurationSnapshotProvider {

    /**
     * Resolve the central main model, write the session {@code inference-config.json} and return its handle.
     *
     * @throws InferenceConfigurationException when no main model is selected, it cannot be resolved to a
     *                                         serving endpoint, or the snapshot cannot be written
     */
    InferenceConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory)
            throws InferenceConfigurationException;
}

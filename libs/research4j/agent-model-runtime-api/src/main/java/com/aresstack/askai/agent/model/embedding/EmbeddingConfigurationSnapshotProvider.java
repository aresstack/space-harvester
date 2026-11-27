package com.aresstack.askai.agent.model.embedding;

import java.io.File;

/**
 * The NEUTRAL host port through which AskAI publishes a session's EMBEDDING endpoint — the embedding counterpart
 * of the reranker/inference snapshot providers. The host validates the explicitly selected embedding-capable
 * local model, ensures its runtime is started, resolves its actual dimension/normalization, and returns only the
 * neutral {@link EmbeddingConfigurationSnapshot} (a callable {@link EmbeddingEndpointDescriptor} + an optional
 * path).
 *
 * <p>{@code research-knowledge-processing} obtains this port from the host and therefore never reads AskAI
 * config files or the model registry itself, and never holds a global static config — the snapshot is always
 * host-injected. Preparing a snapshot NEVER silently falls back to a "first found" model; without a usable
 * embedding model it throws {@link EmbeddingConfigurationException}.</p>
 */
public interface EmbeddingConfigurationSnapshotProvider {

    /**
     * Prepare and publish the embedding snapshot for a session.
     *
     * @param sessionId        the session identifier (diagnostics/labelling only)
     * @param sessionDirectory the session's own directory; any snapshot file is written beneath it
     * @param selectedModel    the EXPLICITLY selected virtual embedding-model id; the host validates it against
     *                         the installed embedding-capable models — an empty/removed/incompatible selection
     *                         fails, it is never replaced by a guess
     * @return the published snapshot (descriptor + optional path)
     * @throws EmbeddingConfigurationException if the selection is missing or unusable, the runtime cannot be
     *                                         started, or the snapshot cannot be written
     */
    EmbeddingConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                     String selectedModel)
            throws EmbeddingConfigurationException;
}

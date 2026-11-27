package com.aresstack.askai.agent.model.reranker;

import java.io.File;

/**
 * The NEUTRAL host port through which the research agent host publishes a mandatory reranker start
 * snapshot for a session. The host implementation validates the explicitly selected rerank-capable
 * local model, ensures its runtime is started, and writes an atomic per-session snapshot; it returns
 * only the neutral {@link RerankerConfigurationSnapshot} (a file path plus the validated document).
 *
 * <p>The agent plugin obtains this port via {@code AgentHostContext.getService(...)} and therefore never
 * depends on the host application, its model manager, or any GPU/runtime types. Preparing a snapshot
 * NEVER silently falls back to a "first found" model: without an explicitly usable rerank model the call
 * throws {@link RerankerConfigurationException} and the productive session start fails visibly.
 */
public interface RerankerConfigurationSnapshotProvider {

    /**
     * Prepare and publish the reranker snapshot for the given session.
     *
     * @param sessionId        the session identifier (diagnostics/labelling only)
     * @param sessionDirectory the session's own directory; the snapshot is written beneath it
     * @param selectedModel    the EXPLICITLY selected virtual model id (persisted research runtime
     *                         setting); the host validates it against the installed models — an empty,
     *                         removed or incompatible selection fails, it is never replaced by a guess
     * @return the published snapshot (absolute file path + validated document)
     * @throws RerankerConfigurationException if the selection is missing or not usable, the runtime
     *                                        cannot be started, or the snapshot cannot be written
     */
    RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                    String selectedModel)
            throws RerankerConfigurationException;

    /**
     * As {@link #prepareForSession(String, File, String)} but with the session's RESEARCH LANGUAGE, so the
     * resolution MAY select a language-appropriate reranker model (e.g. a multilingual/German cross-encoder for
     * {@code "de"} instead of an English-trained MS-MARCO model). The language selector itself never knows a
     * reranker implementation — the mapping language→model belongs entirely to the provider/configuration.
     *
     * <p>Contract: when a language-specific selection is CONFIGURED but not usable, the provider throws
     * {@link RerankerConfigurationException} (never a silent fallback to another language's model). The default
     * ignores the language and resolves the single configured selection — the deliberate initial configuration
     * "en/de → the same explicitly selected reranker" until per-language selections exist.</p>
     *
     * @param languageCode the session research language ISO code ({@code "en"}/{@code "de"}); null/empty = "en"
     */
    default RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                            String selectedModel, String languageCode)
            throws RerankerConfigurationException {
        return prepareForSession(sessionId, sessionDirectory, selectedModel);
    }
}

package com.aresstack.askai.agent.model.nlp;

/**
 * The NEUTRAL host port through which AskAI resolves the EXPLICITLY selected, installed NLP model for a
 * (capability, language) — the NLP counterpart of the embedding/reranker snapshot providers. The host validates
 * the selection against the installed models, checks the artifact exists (and its checksum), and returns only the
 * neutral {@link NlpConfigurationSnapshot}.
 *
 * <p>{@code research-text-opennlp} obtains this port from the host and therefore never reads AskAI config, the
 * model store or a catalog. It NEVER downloads at resolve time: a missing model is a typed
 * {@link NlpConfigurationException} ({@code MODEL_NOT_CONFIGURED}/{@code MODEL_NOT_INSTALLED}) that the caller may
 * treat as "use the regex fallback"; a broken/tampered artifact ({@code ARTIFACT_MISSING}/{@code CHECKSUM_MISMATCH})
 * surfaces instead of degrading silently.</p>
 */
public interface NlpConfigurationSnapshotProvider {

    /**
     * Resolve the installed model selected for {@code capability} + {@code languageCode} into an immutable snapshot.
     *
     * @throws NlpConfigurationException with a typed reason when no usable model is configured/installed, or the
     *                                   selected artifact is missing or fails its checksum
     */
    NlpConfigurationSnapshot resolve(NlpCapability capability, String languageCode)
            throws NlpConfigurationException;
}

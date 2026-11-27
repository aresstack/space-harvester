package com.aresstack.askai.agent.model.nlp;

/**
 * The user's EXPLICIT choice of which installed NLP model to use for one (capability, language) pair — the NLP
 * counterpart of {@code ai.rerankerModel} / {@code ai.embeddingsModel}, but keyed per capability AND language
 * (sentence detection needs a German model AND an English model, so a single global slot would be too coarse).
 */
public final class NlpModelSelection {

    private final NlpCapability capability;
    private final String languageCode;
    private final String modelId;

    public NlpModelSelection(NlpCapability capability, String languageCode, String modelId) {
        if (capability == null) {
            throw new IllegalArgumentException("capability is required");
        }
        this.capability = capability;
        this.languageCode = languageCode == null ? "" : languageCode.trim().toLowerCase();
        this.modelId = modelId == null ? "" : modelId.trim();
    }

    public NlpCapability getCapability() {
        return capability;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public String getModelId() {
        return modelId;
    }

    public boolean isConfigured() {
        return !modelId.isEmpty();
    }
}

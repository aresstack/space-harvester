package com.aresstack.askai.agent.model.nlp;

/**
 * A static NLP capability provided by a locally deployed model ARTIFACT (not a runtime/sidecar model). The first
 * slice ships only {@link #SENTENCE_DETECTION}; the enum is the extension point for later tokenizer / POS / NER
 * capabilities, so the whole SPI (selection, descriptor, catalog, snapshot) is capability-parameterised from the
 * start and never has to be re-shaped to add one.
 */
public enum NlpCapability {

    /** Sentence boundary detection (OpenNLP {@code SentenceModel}). */
    SENTENCE_DETECTION("sentence-detection"),

    /** Language identification of a text passage (OpenNLP {@code LanguageDetectorModel}). */
    LANGUAGE_DETECTION("language-detection");

    private final String tag;

    NlpCapability(String tag) {
        this.tag = tag;
    }

    /** The stable lowercase tag used in manifests / catalog metadata (never the enum name). */
    public String getTag() {
        return tag;
    }

    public static NlpCapability fromTag(String tag) {
        for (NlpCapability capability : values()) {
            if (capability.tag.equals(tag)) {
                return capability;
            }
        }
        throw new IllegalArgumentException("unknown NLP capability tag: " + tag);
    }
}

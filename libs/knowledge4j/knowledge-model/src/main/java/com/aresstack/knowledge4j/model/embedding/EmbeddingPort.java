package com.aresstack.knowledge4j.model.embedding;

import java.util.List;

/**
 * Neutral BATCH embedding port. Every vector carries its model identity — vectors of different
 * fingerprints or dimensions must NEVER be compared.
 *
 * <p>Every call states its {@link Role}: models with query and passage prompts (nomic-embed-text, E5, ...) need
 * them, and no call site may keep an implicit role. Passages and the sentence windows that place passage
 * boundaries are {@link Role#DOCUMENT documents}; texts that are ranked AGAINST documents (a search query, a
 * concept label path, a section brief) are {@link Role#QUERY queries}; the scope fence compares its texts only
 * with each other on calibrated raw vectors and stays {@link Role#RAW raw}. The adapter folds the prompt scheme
 * into the vector fingerprint, so raw vectors and prompted vectors of the same model are never compared.</p>
 */
public interface EmbeddingPort {

    final class EmbeddingVector {
        private final String modelId;
        private final String modelFingerprint;
        private final float[] values;

        public EmbeddingVector(String modelId, String modelFingerprint, float[] values) {
            this.modelId = modelId == null ? "" : modelId;
            this.modelFingerprint = modelFingerprint == null ? "" : modelFingerprint;
            this.values = values == null ? new float[0] : values.clone();
        }

        public String getModelId() {
            return modelId;
        }

        public String getModelFingerprint() {
            return modelFingerprint;
        }

        public int getDimension() {
            return values.length;
        }

        public float[] getValues() {
            return values.clone();
        }
    }

    /** The role of the embedded texts; it selects the model's documented prompt. */
    enum Role {
        /** A passage or any text that is retrieved (also both sides of document-to-document comparisons). */
        DOCUMENT,
        /** A text that is ranked against documents. */
        QUERY,
        /** No prompt at all (the scope fence's calibrated vector world). */
        RAW
    }

    /** One vector per input text, in input order, embedded in the given role. */
    List<EmbeddingVector> embed(Role role, List<String> texts);

    /** Passages and sentence windows. */
    default List<EmbeddingVector> embedDocuments(List<String> texts) {
        return embed(Role.DOCUMENT, texts);
    }

    /** Texts ranked against documents. */
    default List<EmbeddingVector> embedQueries(List<String> texts) {
        return embed(Role.QUERY, texts);
    }

    /** Texts that are compared only with other raw texts (scope fence). */
    default List<EmbeddingVector> embedRaw(List<String> texts) {
        return embed(Role.RAW, texts);
    }
}

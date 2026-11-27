package com.aresstack.writing4j.cluster;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic test embedding: one dimension per vocabulary keyword, value = occurrence count. Texts
 * about the same keywords land close together, disjoint keyword sets are orthogonal — semantically
 * meaningful enough to prove segmentation and clustering without any model.
 */
final class VocabularyEmbeddingPort implements EmbeddingPort {

    private final String[] vocabulary;

    VocabularyEmbeddingPort(String... vocabulary) {
        this.vocabulary = vocabulary;
    }

    @Override
    public List<EmbeddingVector> embed(Role role, List<String> texts) {
        List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
        for (String text : texts) {
            String lower = text.toLowerCase(Locale.ROOT);
            float[] values = new float[vocabulary.length];
            for (int i = 0; i < vocabulary.length; i++) {
                int from = 0;
                while ((from = lower.indexOf(vocabulary[i], from)) >= 0) {
                    values[i]++;
                    from += vocabulary[i].length();
                }
            }
            vectors.add(new EmbeddingVector("test-vocab", "test-vocab-v1", values));
        }
        return vectors;
    }
}

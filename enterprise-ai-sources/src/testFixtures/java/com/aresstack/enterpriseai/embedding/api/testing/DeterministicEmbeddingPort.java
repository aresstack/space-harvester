package com.aresstack.enterpriseai.embedding.api.testing;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;
import com.aresstack.enterpriseai.embedding.api.EmbeddingBatch;
import com.aresstack.enterpriseai.embedding.api.EmbeddingInputs;
import com.aresstack.enterpriseai.embedding.api.EmbeddingPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Test-Fake für {@link EmbeddingPort} ohne Netz: hasht die Wörter eines Textes in einen Vektor
 * (Bag-of-Words, L2-normalisiert). Gleicher Text ergibt denselben Vektor, Texte mit gemeinsamen Wörtern sind
 * ähnlicher als Texte ohne. Ein Text ohne Wörter ergibt einen festen Einheitsvektor, nie einen Null-Vektor.
 * Zeichnet jeden Aufruf auf, damit Tests Batch-Größen und Reihenfolge prüfen können.
 */
public final class DeterministicEmbeddingPort implements EmbeddingPort {

    private final EmbeddingModelIdentity identity;
    private final List<List<String>> calls = new ArrayList<List<String>>();

    public DeterministicEmbeddingPort(EmbeddingModelIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        this.identity = identity;
    }

    /** Bequemer Fake mit Modell-ID {@code fake-embedding} und der angegebenen Dimension. */
    public static DeterministicEmbeddingPort withDimension(int dimension) {
        return new DeterministicEmbeddingPort(EmbeddingModelIdentity.of("fake-embedding", dimension));
    }

    @Override
    public EmbeddingModelIdentity modelIdentity() {
        return identity;
    }

    @Override
    public synchronized EmbeddingBatch embed(List<String> texts) {
        List<String> inputs = EmbeddingInputs.requireValid(texts);
        if (inputs.isEmpty()) {
            return EmbeddingBatch.empty(identity);
        }
        calls.add(inputs);
        List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>(inputs.size());
        for (String text : inputs) {
            vectors.add(vectorFor(text));
        }
        return EmbeddingBatch.of(identity, inputs.size(), vectors);
    }

    /** Der Vektor, den dieser Fake für {@code text} liefert. */
    public EmbeddingVector vectorFor(String text) {
        float[] values = new float[identity.dimension()];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                int h = token.hashCode() * 0x9E3779B1;
                values[(h >>> 1) % values.length] += (h & 1) == 0 ? 1f : -1f;
            }
        }
        double norm = 0;
        for (float v : values) {
            norm += v * v;
        }
        if (norm == 0) {
            values[0] = 1f;
            norm = 1;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < values.length; i++) {
            values[i] *= scale;
        }
        return EmbeddingVector.of(identity, values);
    }

    /** Alle nicht-leeren Aufrufe in Aufrufreihenfolge. */
    public synchronized List<List<String>> calls() {
        return Collections.unmodifiableList(new ArrayList<List<String>>(calls));
    }
}

package com.aresstack.enterpriseai.embedding.api;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geprüftes Ergebnis eines {@link EmbeddingPort#embed}-Aufrufs: genau ein Vektor je Eingabe, in
 * Eingabereihenfolge, alle aus derselben Embedding-Welt. Adapter und Test-Fakes bauen ihr Ergebnis über
 * {@link #of}, damit diese Garantien an einer Stelle durchgesetzt werden.
 */
public final class EmbeddingBatch {

    private final EmbeddingModelIdentity identity;
    private final List<EmbeddingVector> vectors;

    private EmbeddingBatch(EmbeddingModelIdentity identity, List<EmbeddingVector> vectors) {
        this.identity = identity;
        this.vectors = vectors;
    }

    /**
     * @param identity      erwartete Embedding-Welt
     * @param expectedCount Anzahl der Eingabetexte
     * @param vectors       Vektoren in Eingabereihenfolge
     * @throws EmbeddingException ({@link EmbeddingFailureKind#INVALID_RESPONSE}) wenn Anzahl, Welt oder ein
     *                            Element nicht passt
     */
    public static EmbeddingBatch of(EmbeddingModelIdentity identity, int expectedCount, List<EmbeddingVector> vectors) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (vectors == null) {
            throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, "no vectors returned");
        }
        if (vectors.size() != expectedCount) {
            throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE,
                    "embedding count " + vectors.size() + " does not match input count " + expectedCount);
        }
        List<EmbeddingVector> copy = new ArrayList<EmbeddingVector>(vectors.size());
        int i = 0;
        for (EmbeddingVector vector : vectors) {
            if (vector == null) {
                throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE, "vector " + i + " is missing");
            }
            if (!identity.isSameWorldAs(vector.identity())) {
                throw new EmbeddingException(EmbeddingFailureKind.INVALID_RESPONSE,
                        "vector " + i + " belongs to " + vector.identity() + ", expected " + identity);
            }
            copy.add(vector);
            i++;
        }
        return new EmbeddingBatch(identity, Collections.unmodifiableList(copy));
    }

    /** Leerer Batch für eine leere Eingabeliste. */
    public static EmbeddingBatch empty(EmbeddingModelIdentity identity) {
        return of(identity, 0, Collections.<EmbeddingVector>emptyList());
    }

    public EmbeddingModelIdentity identity() {
        return identity;
    }

    public int size() {
        return vectors.size();
    }

    public boolean isEmpty() {
        return vectors.isEmpty();
    }

    /** Vektor zum Eingabetext mit Index {@code index}. */
    public EmbeddingVector get(int index) {
        return vectors.get(index);
    }

    /** Unveränderliche Liste in Eingabereihenfolge. */
    public List<EmbeddingVector> vectors() {
        return vectors;
    }

    @Override
    public String toString() {
        return "EmbeddingBatch{size=" + vectors.size() + ", identity=" + identity + "}";
    }
}

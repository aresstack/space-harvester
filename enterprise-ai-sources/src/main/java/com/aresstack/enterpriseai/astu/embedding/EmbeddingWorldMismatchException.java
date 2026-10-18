package com.aresstack.enterpriseai.astu.embedding;

/**
 * Zwei Vektoren oder Identitäten gehören zu verschiedenen Embedding-Welten und dürfen nicht miteinander
 * verrechnet werden. Ein Vergleich über Weltgrenzen ist immer ein Programmierfehler, nie Rauschen.
 */
public final class EmbeddingWorldMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public EmbeddingWorldMismatchException(EmbeddingModelIdentity expected, EmbeddingModelIdentity actual) {
        super("vectors of different embedding worlds must never be compared: " + expected + " vs " + actual);
    }
}

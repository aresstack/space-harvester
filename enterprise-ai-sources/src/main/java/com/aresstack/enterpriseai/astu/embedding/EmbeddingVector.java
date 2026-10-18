package com.aresstack.enterpriseai.astu.embedding;

import java.util.Arrays;

/**
 * Ein unveränderlicher Embedding-Vektor mit seiner {@link EmbeddingModelIdentity}.
 *
 * <p>Invarianten: Die Länge entspricht genau {@link EmbeddingModelIdentity#dimension()}, alle Komponenten sind
 * endlich (kein NaN, kein ±Infinity). Rechenoperationen mit einem zweiten Vektor verlangen dieselbe Identität
 * und werfen sonst {@link EmbeddingWorldMismatchException}.
 *
 * <p>Konzept übernommen aus askai-java8 ({@code EmbeddingPort.EmbeddingVector}, {@code VectorMath}); hier mit
 * vollständiger Identität statt loser Modell-ID/Fingerprint-Strings und mit Prüfung bei der Konstruktion.
 */
public final class EmbeddingVector {

    private final EmbeddingModelIdentity identity;
    private final float[] values;

    private EmbeddingVector(EmbeddingModelIdentity identity, float[] values) {
        this.identity = identity;
        this.values = values;
    }

    /**
     * @throws IllegalArgumentException bei falscher Dimension oder nicht-endlichen Werten
     */
    public static EmbeddingVector of(EmbeddingModelIdentity identity, float[] values) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        if (values.length != identity.dimension()) {
            throw new IllegalArgumentException("vector has dimension " + values.length
                    + " but its embedding world " + identity.modelId() + " requires " + identity.dimension());
        }
        float[] copy = values.clone();
        for (int i = 0; i < copy.length; i++) {
            if (Float.isNaN(copy[i]) || Float.isInfinite(copy[i])) {
                throw new IllegalArgumentException("vector component " + i + " is not finite: " + copy[i]);
            }
        }
        return new EmbeddingVector(identity, copy);
    }

    public EmbeddingModelIdentity identity() {
        return identity;
    }

    public int dimension() {
        return values.length;
    }

    /** Kopie der Komponenten. */
    public float[] values() {
        return values.clone();
    }

    public float valueAt(int index) {
        return values[index];
    }

    /** Euklidische Norm. */
    public double norm() {
        double sum = 0;
        for (float v : values) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    /** Skalarprodukt; verlangt dieselbe Embedding-Welt. */
    public double dot(EmbeddingVector other) {
        requireSameWorld(other);
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += (double) values[i] * other.values[i];
        }
        return sum;
    }

    /**
     * Cosine-Ähnlichkeit im Bereich [-1, 1]; verlangt dieselbe Embedding-Welt. Hat einer der Vektoren die
     * Norm 0, ist die Ähnlichkeit 0.
     */
    public double cosineSimilarity(EmbeddingVector other) {
        double dot = dot(other);
        double norms = norm() * other.norm();
        if (norms == 0) {
            return 0;
        }
        double cosine = dot / norms;
        return Math.max(-1.0, Math.min(1.0, cosine));
    }

    /** Wahr genau dann, wenn {@code other} zur selben Embedding-Welt gehört. */
    public boolean isComparableWith(EmbeddingVector other) {
        return other != null && identity.isSameWorldAs(other.identity);
    }

    private void requireSameWorld(EmbeddingVector other) {
        if (other == null) {
            throw new IllegalArgumentException("other vector must not be null");
        }
        identity.requireSameWorldAs(other.identity);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EmbeddingVector)) {
            return false;
        }
        EmbeddingVector that = (EmbeddingVector) o;
        return identity.equals(that.identity) && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * identity.hashCode() + Arrays.hashCode(values);
    }

    /** Ohne Komponenten, damit Logs nicht mit Tausenden Zahlen geflutet werden. */
    @Override
    public String toString() {
        return "EmbeddingVector{model=" + identity.modelId() + ", dimension=" + values.length
                + ", fingerprint=" + identity.fingerprint().substring(0, 12) + "}";
    }
}

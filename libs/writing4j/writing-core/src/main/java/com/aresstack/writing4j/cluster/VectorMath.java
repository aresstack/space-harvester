package com.aresstack.writing4j.cluster;

import com.aresstack.knowledge4j.model.embedding.EmbeddingPort;

import java.util.List;

/** Cosine similarity with the fingerprint guard — cross-model comparison is a hard error, never noise. */
public final class VectorMath {

    private VectorMath() {
    }

    public static double cosine(EmbeddingPort.EmbeddingVector a, EmbeddingPort.EmbeddingVector b) {
        if (!a.getModelFingerprint().equals(b.getModelFingerprint())
                || a.getDimension() != b.getDimension()) {
            throw new IllegalArgumentException("vectors of different embedding models/dimensions must "
                    + "never be compared: " + a.getModelFingerprint() + "/" + a.getDimension()
                    + " vs " + b.getModelFingerprint() + "/" + b.getDimension());
        }
        float[] x = a.getValues();
        float[] y = b.getValues();
        double dot = 0;
        double normX = 0;
        double normY = 0;
        for (int i = 0; i < x.length; i++) {
            dot += x[i] * y[i];
            normX += x[i] * x[i];
            normY += y[i] * y[i];
        }
        if (normX == 0 || normY == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normX) * Math.sqrt(normY));
    }

    /** The arithmetic mean vector (same fingerprint enforced pairwise via cosine callers). */
    public static EmbeddingPort.EmbeddingVector mean(List<EmbeddingPort.EmbeddingVector> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("cannot average zero vectors");
        }
        EmbeddingPort.EmbeddingVector first = vectors.get(0);
        float[] sum = new float[first.getDimension()];
        for (EmbeddingPort.EmbeddingVector vector : vectors) {
            if (!vector.getModelFingerprint().equals(first.getModelFingerprint())
                    || vector.getDimension() != first.getDimension()) {
                throw new IllegalArgumentException("mixed embedding models in mean()");
            }
            float[] raw = vector.getValues();
            for (int i = 0; i < raw.length; i++) {
                sum[i] += raw[i];
            }
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= vectors.size();
        }
        return new EmbeddingPort.EmbeddingVector(first.getModelId(), first.getModelFingerprint(), sum);
    }
}

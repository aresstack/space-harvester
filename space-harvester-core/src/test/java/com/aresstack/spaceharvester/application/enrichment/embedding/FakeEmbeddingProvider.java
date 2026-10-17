package com.aresstack.spaceharvester.application.enrichment.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Deterministischer Ersatz eines Modells: Vektor aus dem SHA-256 des Textes, ohne Netz und ohne Modelldatei. */
public final class FakeEmbeddingProvider implements EmbeddingProvider {

    private final String modelId;
    private final int dimensions;
    final List<List<String>> calls = new ArrayList<>();

    public FakeEmbeddingProvider(String modelId, int dimensions) {
        this.modelId = modelId;
        this.dimensions = dimensions;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        calls.add(List.copyOf(texts));
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) {
            byte[] digest = sha256((modelId + "\n" + text).getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = (digest[i % digest.length] & 0xFF) / 255f;
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

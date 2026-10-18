package com.aresstack.enterpriseai.astu.embedding;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EmbeddingVectorTest {

    private static final EmbeddingModelIdentity WORLD = EmbeddingModelIdentity.of("m", 3);

    @Test
    public void acceptsFiniteValuesOfTheRightDimension() {
        EmbeddingVector v = EmbeddingVector.of(WORLD, new float[] {1f, -2.5f, 0f});
        assertEquals(3, v.dimension());
        assertEquals(WORLD, v.identity());
        assertEquals(-2.5f, v.valueAt(1), 0f);
    }

    @Test
    public void rejectsWrongDimension() {
        assertRejected(WORLD, new float[] {1f, 2f});
        assertRejected(WORLD, new float[] {1f, 2f, 3f, 4f});
        assertRejected(WORLD, new float[0]);
    }

    @Test
    public void rejectsNaNAndInfinity() {
        assertRejected(WORLD, new float[] {1f, Float.NaN, 0f});
        assertRejected(WORLD, new float[] {Float.POSITIVE_INFINITY, 0f, 0f});
        assertRejected(WORLD, new float[] {0f, 0f, Float.NEGATIVE_INFINITY});
    }

    @Test
    public void rejectsNulls() {
        assertRejected(null, new float[] {1f, 2f, 3f});
        assertRejected(WORLD, null);
    }

    @Test
    public void isImmutable() {
        float[] source = {1f, 2f, 3f};
        EmbeddingVector v = EmbeddingVector.of(WORLD, source);
        source[0] = 99f;
        v.values()[1] = 99f;
        assertArrayEquals(new float[] {1f, 2f, 3f}, v.values(), 0f);
    }

    @Test
    public void cosineSimilarityWithinOneWorld() {
        EmbeddingVector a = EmbeddingVector.of(WORLD, new float[] {1f, 0f, 0f});
        EmbeddingVector b = EmbeddingVector.of(WORLD, new float[] {0f, 1f, 0f});
        EmbeddingVector c = EmbeddingVector.of(WORLD, new float[] {2f, 0f, 0f});
        EmbeddingVector d = EmbeddingVector.of(WORLD, new float[] {-1f, 0f, 0f});
        EmbeddingVector zero = EmbeddingVector.of(WORLD, new float[] {0f, 0f, 0f});

        assertEquals(0.0, a.cosineSimilarity(b), 1e-9);
        assertEquals(1.0, a.cosineSimilarity(c), 1e-9);
        assertEquals(-1.0, a.cosineSimilarity(d), 1e-9);
        assertEquals(0.0, a.cosineSimilarity(zero), 1e-9);
        assertEquals(2.0, a.dot(c), 1e-9);
        assertEquals(2.0, c.norm(), 1e-9);
    }

    @Test
    public void vectorsOfDifferentFingerprintsCannotBeCompared() {
        // gleiche Modell-ID und Dimension, aber andere Konfiguration -> andere Welt
        EmbeddingModelIdentity other = WORLD.withAttribute("normalization", "l2");
        EmbeddingVector a = EmbeddingVector.of(WORLD, new float[] {1f, 0f, 0f});
        EmbeddingVector b = EmbeddingVector.of(other, new float[] {1f, 0f, 0f});

        assertFalse(a.isComparableWith(b));
        assertFalse(a.equals(b));
        try {
            a.cosineSimilarity(b);
            fail("cross-world cosine must fail");
        } catch (EmbeddingWorldMismatchException expected) {
            // ok
        }
        try {
            b.dot(a);
            fail("cross-world dot must fail");
        } catch (EmbeddingWorldMismatchException expected) {
            // ok
        }
    }

    @Test
    public void vectorsOfDifferentDimensionsCannotBeCompared() {
        EmbeddingVector a = EmbeddingVector.of(WORLD, new float[] {1f, 0f, 0f});
        EmbeddingVector b = EmbeddingVector.of(EmbeddingModelIdentity.of("m", 2), new float[] {1f, 0f});
        assertFalse(a.isComparableWith(b));
        try {
            a.cosineSimilarity(b);
            fail("cross-dimension cosine must fail");
        } catch (EmbeddingWorldMismatchException expected) {
            // ok
        }
    }

    @Test
    public void equalityAndToString() {
        EmbeddingVector a = EmbeddingVector.of(WORLD, new float[] {1f, 2f, 3f});
        EmbeddingVector b = EmbeddingVector.of(EmbeddingModelIdentity.of("m", 3), new float[] {1f, 2f, 3f});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.isComparableWith(b));
        assertFalse(a.toString().contains("2.0"));
        assertTrue(a.toString().contains("dimension=3"));
    }

    private static void assertRejected(EmbeddingModelIdentity identity, float[] values) {
        try {
            EmbeddingVector.of(identity, values);
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}

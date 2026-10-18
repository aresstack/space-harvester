package com.aresstack.enterpriseai.embedding.api;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EmbeddingBatchTest {

    private static final EmbeddingModelIdentity WORLD = EmbeddingModelIdentity.of("m", 2);

    @Test
    public void keepsInputOrder() {
        EmbeddingVector first = vector(WORLD, 1f);
        EmbeddingVector second = vector(WORLD, 2f);
        EmbeddingVector third = vector(WORLD, 3f);

        EmbeddingBatch batch = EmbeddingBatch.of(WORLD, 3, Arrays.asList(first, second, third));

        assertEquals(3, batch.size());
        assertSame(first, batch.get(0));
        assertSame(second, batch.get(1));
        assertSame(third, batch.get(2));
        assertEquals(Arrays.asList(first, second, third), batch.vectors());
    }

    @Test
    public void isNotAffectedByLaterChangesOfTheSourceList() {
        List<EmbeddingVector> source = new ArrayList<EmbeddingVector>(Collections.singletonList(vector(WORLD, 1f)));
        EmbeddingBatch batch = EmbeddingBatch.of(WORLD, 1, source);
        source.clear();
        assertEquals(1, batch.size());
        try {
            batch.vectors().clear();
            fail("vectors must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void rejectsCountMismatch() {
        assertInvalid(WORLD, 2, Collections.singletonList(vector(WORLD, 1f)));
        assertInvalid(WORLD, 0, Collections.singletonList(vector(WORLD, 1f)));
        assertInvalid(WORLD, 1, null);
    }

    @Test
    public void rejectsMissingVector() {
        assertInvalid(WORLD, 2, Arrays.asList(vector(WORLD, 1f), null));
    }

    @Test
    public void rejectsVectorsOfAnotherWorld() {
        EmbeddingModelIdentity other = WORLD.withAttribute("version", "2");
        assertInvalid(WORLD, 2, Arrays.asList(vector(WORLD, 1f), vector(other, 2f)));
        assertInvalid(WORLD, 1, Collections.singletonList(
                EmbeddingVector.of(EmbeddingModelIdentity.of("m", 3), new float[] {1f, 0f, 0f})));
    }

    @Test
    public void emptyBatch() {
        EmbeddingBatch batch = EmbeddingBatch.empty(WORLD);
        assertTrue(batch.isEmpty());
        assertEquals(WORLD, batch.identity());
    }

    @Test
    public void inputsAreValidatedAndCopied() {
        List<String> texts = new ArrayList<String>(Arrays.asList("a", "b"));
        List<String> copy = EmbeddingInputs.requireValid(texts);
        texts.add("c");
        assertEquals(Arrays.asList("a", "b"), copy);
        try {
            EmbeddingInputs.requireValid(Arrays.asList("a", null));
            fail("null element must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("1"));
        }
        try {
            EmbeddingInputs.requireValid(null);
            fail("null list must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void failureKindsDeclareRetryability() {
        assertTrue(new EmbeddingException(EmbeddingFailureKind.UNAVAILABLE, "x").isRetryable());
        assertTrue(EmbeddingFailureKind.RATE_LIMITED.isRetryable());
        assertTrue(EmbeddingFailureKind.PROVIDER_ERROR.isRetryable());
        assertFalse(EmbeddingFailureKind.AUTHENTICATION.isRetryable());
        assertFalse(EmbeddingFailureKind.REJECTED.isRetryable());
        assertFalse(EmbeddingFailureKind.INVALID_RESPONSE.isRetryable());
    }

    private static EmbeddingVector vector(EmbeddingModelIdentity identity, float first) {
        return EmbeddingVector.of(identity, new float[] {first, 0f});
    }

    private static void assertInvalid(EmbeddingModelIdentity identity, int count, List<EmbeddingVector> vectors) {
        try {
            EmbeddingBatch.of(identity, count, vectors);
            fail("expected INVALID_RESPONSE");
        } catch (EmbeddingException expected) {
            assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, expected.kind());
        }
    }
}

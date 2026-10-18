package com.aresstack.enterpriseai.embedding.api.testing;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingModelIdentity;
import com.aresstack.enterpriseai.embedding.api.EmbeddingBatch;
import com.aresstack.enterpriseai.embedding.api.EmbeddingPort;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Vertragstest, den jede {@link EmbeddingPort}-Implementierung erben soll. Voraussetzung: Die Implementierung
 * (bzw. ihr Fake-Backend) liefert für denselben Text deterministisch denselben Vektor.
 */
public abstract class EmbeddingPortContractTest {

    /** Ein frischer, betriebsbereiter Port. */
    protected abstract EmbeddingPort newPort();

    @Test
    public void returnsOneVectorPerInputInInputOrder() {
        EmbeddingPort port = newPort();
        List<String> texts = Arrays.asList("alpha", "beta gamma", "delta", "äöü ß – UTF-8 ✓");
        EmbeddingBatch forward = port.embed(texts);

        List<String> reversedTexts = new ArrayList<String>(texts);
        Collections.reverse(reversedTexts);
        EmbeddingBatch backward = port.embed(reversedTexts);

        assertEquals(texts.size(), forward.size());
        assertEquals(texts.size(), backward.size());
        for (int i = 0; i < texts.size(); i++) {
            assertEquals("vector of input " + i, forward.get(i), backward.get(texts.size() - 1 - i));
        }
    }

    @Test
    public void batchResultEqualsSingleResults() {
        EmbeddingPort port = newPort();
        List<String> texts = Arrays.asList("one", "two", "three");
        EmbeddingBatch batch = port.embed(texts);
        for (int i = 0; i < texts.size(); i++) {
            assertEquals(batch.get(i), port.embed(Collections.singletonList(texts.get(i))).get(0));
        }
    }

    @Test
    public void everyVectorCarriesThePortIdentity() {
        EmbeddingPort port = newPort();
        EmbeddingModelIdentity identity = port.modelIdentity();
        EmbeddingBatch batch = port.embed(Arrays.asList("a", "b"));
        assertEquals(identity, batch.identity());
        for (int i = 0; i < batch.size(); i++) {
            assertEquals(identity, batch.get(i).identity());
            assertEquals(identity.dimension(), batch.get(i).dimension());
        }
    }

    @Test
    public void emptyInputGivesEmptyBatch() {
        EmbeddingPort port = newPort();
        EmbeddingBatch batch = port.embed(Collections.<String>emptyList());
        assertTrue(batch.isEmpty());
        assertEquals(port.modelIdentity(), batch.identity());
    }

    @Test
    public void rejectsNullInput() {
        EmbeddingPort port = newPort();
        try {
            port.embed(null);
            fail("null list must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            port.embed(Arrays.asList("ok", null));
            fail("null element must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}

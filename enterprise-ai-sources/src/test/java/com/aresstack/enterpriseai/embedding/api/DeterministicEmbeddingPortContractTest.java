package com.aresstack.enterpriseai.embedding.api;

import com.aresstack.enterpriseai.astu.embedding.EmbeddingVector;
import com.aresstack.enterpriseai.embedding.api.testing.DeterministicEmbeddingPort;
import com.aresstack.enterpriseai.embedding.api.testing.EmbeddingPortContractTest;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Der Testfixture-Fake erfüllt selbst den Port-Vertrag. */
public class DeterministicEmbeddingPortContractTest extends EmbeddingPortContractTest {

    @Override
    protected EmbeddingPort newPort() {
        return DeterministicEmbeddingPort.withDimension(16);
    }

    @Test
    public void similarTextsAreCloserThanUnrelatedOnes() {
        DeterministicEmbeddingPort port = DeterministicEmbeddingPort.withDimension(64);
        EmbeddingBatch batch = port.embed(Arrays.asList(
                "Lucene BM25 Volltextsuche", "BM25 Suche mit Lucene", "Kaffeemaschine entkalken"));
        EmbeddingVector query = batch.get(0);
        assertTrue(query.cosineSimilarity(batch.get(1)) > query.cosineSimilarity(batch.get(2)));
    }

    @Test
    public void recordsCallsAndSkipsEmptyOnes() {
        DeterministicEmbeddingPort port = DeterministicEmbeddingPort.withDimension(4);
        port.embed(Collections.<String>emptyList());
        port.embed(Arrays.asList("a", "b"));
        assertEquals(Collections.singletonList(Arrays.asList("a", "b")), port.calls());
    }

    @Test
    public void blankTextGivesAUnitVectorNotAZeroVector() {
        EmbeddingVector vector = DeterministicEmbeddingPort.withDimension(4).vectorFor("   ");
        assertEquals(1.0, vector.norm(), 1e-6);
    }
}

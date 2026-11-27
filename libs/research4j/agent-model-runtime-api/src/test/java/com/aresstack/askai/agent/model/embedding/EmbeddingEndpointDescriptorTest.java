package com.aresstack.askai.agent.model.embedding;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The embedding fingerprint identifies the semantic vector world (model+version+dim+norm), never the URL. */
public class EmbeddingEndpointDescriptorTest {

    private static EmbeddingEndpointDescriptor d(String modelId, String baseUrl, int dim, String norm,
                                                 String version) {
        return new EmbeddingEndpointDescriptor(modelId, baseUrl, "/api/embeddings", dim, norm, version, 5000L);
    }

    @Test
    public void sameWorldOnDifferentUrlsIsComparable() {
        EmbeddingEndpointDescriptor a = d("e5-small", "http://127.0.0.1:1111", 384, "l2", "v1");
        EmbeddingEndpointDescriptor b = d("e5-small", "http://127.0.0.1:2222", 384, "l2", "v1");
        assertEquals("url must not affect the world fingerprint",
                a.embeddingFingerprint(), b.embeddingFingerprint());
        assertTrue(a.sameEmbeddingWorldAs(b));
    }

    @Test
    public void differentModelDimensionOrNormalizationAreDifferentWorlds() {
        EmbeddingEndpointDescriptor base = d("e5-small", "http://h", 384, "l2", "v1");
        assertFalse(base.sameEmbeddingWorldAs(d("bge-small", "http://h", 384, "l2", "v1")));   // model
        assertFalse(base.sameEmbeddingWorldAs(d("e5-small", "http://h", 768, "l2", "v1")));    // dimension
        assertFalse(base.sameEmbeddingWorldAs(d("e5-small", "http://h", 384, "none", "v1")));  // normalization
        assertFalse(base.sameEmbeddingWorldAs(d("e5-small", "http://h", 384, "l2", "v2")));    // version
    }

    @Test
    public void endpointUrlComposesBaseAndPathAndStripsTrailingSlash() {
        EmbeddingEndpointDescriptor a = new EmbeddingEndpointDescriptor("m", "http://h:9/", null,
                384, "l2", "", 1000L);
        assertEquals("http://h:9/api/embed", a.endpointUrl());
    }
}

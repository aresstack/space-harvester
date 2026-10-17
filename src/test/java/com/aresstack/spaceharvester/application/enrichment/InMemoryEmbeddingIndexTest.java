package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndexContract;

class InMemoryEmbeddingIndexTest extends EmbeddingIndexContract {

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Override
    protected EnrichmentStore store() {
        return store;
    }

    @Override
    protected EmbeddingIndex index() {
        return store;
    }
}

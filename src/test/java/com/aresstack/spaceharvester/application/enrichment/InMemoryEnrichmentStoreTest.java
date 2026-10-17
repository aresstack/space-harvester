package com.aresstack.spaceharvester.application.enrichment;

class InMemoryEnrichmentStoreTest extends EnrichmentStoreContract {

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Override
    protected EnrichmentStore store() {
        return store;
    }
}

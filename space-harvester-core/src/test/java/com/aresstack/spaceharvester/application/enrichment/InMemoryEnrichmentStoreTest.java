package com.aresstack.spaceharvester.application.enrichment;

class InMemoryEnrichmentStoreTest extends EnrichmentStoreContract {

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Override
    protected EnrichmentStore store() {
        return store;
    }

    @org.junit.jupiter.api.Nested
    class AsFactStore extends com.aresstack.spaceharvester.application.facts.FactStoreContract {

        @Override
        protected EnrichmentStore store() {
            return store;
        }
    }
}

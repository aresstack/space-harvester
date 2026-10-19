package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermIndexContract;
import org.junit.jupiter.api.Nested;

class InMemoryEnrichmentStoreTest extends EnrichmentStoreContract {

    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    @Override
    protected EnrichmentStore store() {
        return store;
    }

    @Nested
    class AsTermIndex extends TermIndexContract {

        @Override
        protected EnrichmentStore store() {
            return store;
        }

        @Override
        protected TermIndex index() {
            return store;
        }
    }

    @Nested
    class AsFactStore extends com.aresstack.spaceharvester.application.facts.FactStoreContract {

        @Override
        protected EnrichmentStore store() {
            return store;
        }
    }
}

package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die deterministischen Anreicherungen (#29): Confluence-Textprojektion, die Prozessoren {@code acronyms}
 * und {@code outline} und die Ablage (Lucene unter {@code enrichments/}, ohne Cache-Verzeichnis flüchtig). Nach jedem
 * bestätigten Abgleich werden sie nachgezogen.
 */
@Configuration(proxyBeanMethods = false)
class EnrichmentConfiguration {

    @Bean
    Enrichments enrichments(ObjectProvider<EnrichmentStore> store) {
        return new Enrichments(new ConfluenceStorageTextProjector(),
                List.of(new AcronymProcessor(), new OutlineProcessor()),
                store.getIfAvailable(InMemoryEnrichmentStore::new), Clock.systemUTC());
    }

    @Bean
    EnrichmentsAfterSync enrichmentsAfterSync(Enrichments enrichments, ObjectProvider<SnapshotRepository> snapshots) {
        return new EnrichmentsAfterSync(enrichments, snapshots.getIfAvailable());
    }
}

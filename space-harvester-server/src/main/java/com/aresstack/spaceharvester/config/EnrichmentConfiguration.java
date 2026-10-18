package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die deterministischen Anreicherungen (#29): Confluence-Textprojektion, die Prozessoren {@code acronyms},
 * {@code outline}, {@code entities}, {@code properties}, {@code activities} und {@code relations} und die Ablage
 * (Lucene unter {@code enrichments/}, ohne Cache-Verzeichnis flüchtig). Nach jedem bestätigten Abgleich werden sie
 * nachgezogen,
 * als eigener Auftrag {@code ENRICHMENT}.
 */
@Configuration(proxyBeanMethods = false)
class EnrichmentConfiguration {

    @Bean
    Enrichments enrichments(ObjectProvider<EnrichmentStore> store, ObjectProvider<EmbeddingProcessor> embedding,
                            @Qualifier("enrichmentBackground") ObjectProvider<ExecutorService> background) {
        List<EnrichmentProcessor> processors = new ArrayList<>(List.of(new AcronymProcessor(), new OutlineProcessor(),
                new EntityProcessor(), new PropertyProcessor(), new ActivityProcessor(), new RelationProcessor()));
        // Embeddings nur mit eingerichtetem KI-Dienst (AiConfiguration, ADR 0011), im Hintergrund
        embedding.ifAvailable(processors::add);
        return new Enrichments(new ConfluenceStorageTextProjector(), processors,
                store.getIfAvailable(InMemoryEnrichmentStore::new), Clock.systemUTC(), background.getIfAvailable());
    }

    @Bean
    EnrichmentsAfterSync enrichmentsAfterSync(Enrichments enrichments, ObjectProvider<SnapshotRepository> snapshots,
                                              ObjectProvider<Jobs> jobs) {
        // Eigener Auftrag ENRICHMENT: Abgleich und Export warten nicht auf die Anreicherung.
        return new EnrichmentsAfterSync(enrichments, snapshots.getIfAvailable(), jobs.getIfAvailable());
    }
}

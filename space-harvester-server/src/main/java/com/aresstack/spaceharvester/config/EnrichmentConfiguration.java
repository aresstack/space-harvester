package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.images.ImageProcessor;
import com.aresstack.spaceharvester.application.images.PageImageAnalysis;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
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
 * {@code outline}, {@code entities}, {@code properties}, {@code activities}, {@code relations} und {@code terms}
 * (Begriffsindex, #119) und die Ablage
 * (Lucene unter {@code enrichments/}, ohne Cache-Verzeichnis flüchtig). Nach jedem bestätigten Abgleich werden sie
 * nachgezogen,
 * als eigener Auftrag {@code ENRICHMENT}.
 */
@Configuration(proxyBeanMethods = false)
class EnrichmentConfiguration {

    @Bean
    Enrichments enrichments(ObjectProvider<EnrichmentStore> store, ObjectProvider<EmbeddingProcessor> embedding,
                            @Qualifier("enrichmentBackground") ObjectProvider<ExecutorService> background,
                            ObjectProvider<ImageDescriber> describer) {
        List<EnrichmentProcessor> processors = new ArrayList<>(List.of(new AcronymProcessor(), new OutlineProcessor(),
                new EntityProcessor(), new PropertyProcessor(), new ActivityProcessor(), new RelationProcessor(),
                new TermProcessor()));
        // Embeddings nur mit eingerichtetem KI-Dienst (AiConfiguration, ADR 0011), im Hintergrund
        embedding.ifAvailable(processors::add);
        // ADR 0021: rechnet nie selbst, bestimmt nur, wann eine Bildanalyse gilt (Modellwechsel macht sie alt)
        processors.add(new ImageProcessor(describer::getIfAvailable));
        // Auch lokale Dateien und Quellen über discover/load (#187) bekommen Anreicherungen und Embeddings, wie im
        // Volltextindex.
        PageTextProjector projector = PageTextProjector.firstOf(List.of(new ConfluenceStorageTextProjector(),
                new LocalFilesTextProjector(), new KnowledgeDocumentTextProjector()));
        return new Enrichments(projector, processors,
                store.getIfAvailable(InMemoryEnrichmentStore::new), Clock.systemUTC(), background.getIfAvailable());
    }

    /** Bildanalyse auf Abruf (ADR 0021, #362). */
    @Bean
    PageImageAnalysis pageImageAnalysis(Assets assets, Enrichments enrichments) {
        return new PageImageAnalysis(assets, enrichments, Clock.systemUTC());
    }

    @Bean
    EnrichmentsAfterSync enrichmentsAfterSync(Enrichments enrichments, ObjectProvider<SnapshotRepository> snapshots,
                                              ObjectProvider<Jobs> jobs, ObjectProvider<PageTextIndexing> texts) {
        // Eigener Auftrag ENRICHMENT: Abgleich und Export warten nicht auf die Anreicherung; er hält auch den
        // Volltextindex nach (#180).
        return new EnrichmentsAfterSync(enrichments, snapshots.getIfAvailable(), jobs.getIfAvailable(),
                texts.getIfAvailable());
    }
}

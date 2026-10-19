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
import com.aresstack.spaceharvester.application.enrichment.VersionRecomputation;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import com.aresstack.spaceharvester.application.enrichment.language.ActivityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.EntityProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.PropertyProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.RelationProcessor;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementProcessor;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationBuilder;
import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.application.passages.NavigationProcessor;
import com.aresstack.spaceharvester.application.passages.NavigationQueries;
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.PassageProcessor;
import com.aresstack.spaceharvester.application.passages.PassageQueries;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.terms.AcronymRelationRule;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die deterministischen Anreicherungen (#29): Confluence-Textprojektion, die Prozessoren {@code acronyms},
 * {@code outline}, {@code entities}, {@code properties}, {@code activities}, {@code relations}, {@code terms}
 * (Begriffsindex, #119), {@code passages} (semantische Absätze, ADR 0020), eingeschaltet {@code statements}
 * (Satzanalyse über das Chat-Modell, #108) und die Ablage
 * (Lucene unter {@code enrichments/}, ohne Cache-Verzeichnis flüchtig). Nach jedem bestätigten Abgleich werden sie
 * nachgezogen,
 * als eigener Auftrag {@code ENRICHMENT}.
 */
@Configuration(proxyBeanMethods = false)
class EnrichmentConfiguration {

    @Bean
    Enrichments enrichments(ObjectProvider<EnrichmentStore> store, ObjectProvider<EmbeddingProcessor> embedding,
                            @Qualifier("enrichmentBackground") ObjectProvider<ExecutorService> background,
                            ObjectProvider<ImageDescriber> describer, SentenceSegmenter sentences,
                            ObjectProvider<NavigationLabelModel> labels,
                            ObjectProvider<StatementProcessor> statements) {
        // Akronyme mit denselben Satzgrenzen wie die Absätze (EAC: OpenNLP, sonst Splitter).
        AcronymProcessor acronyms = new AcronymProcessor(sentences);
        List<EnrichmentProcessor> processors = new ArrayList<>(List.of(acronyms, new OutlineProcessor(),
                new EntityProcessor(), new PropertyProcessor(), new ActivityProcessor(), new RelationProcessor(),
                new TermProcessor(acronyms)));
        // Embeddings über den KI-Dienst (AiConfiguration, ADR 0011), im Hintergrund; ohne Modell bettet er nichts ein
        embedding.ifAvailable(processors::add);
        // #181: semantische Absätze mit dem Modell von embedding, ohne es nach Struktur (ADR 0020)
        TextSegmentation rules = KnowledgeTextSegmentation.defaults();
        processors.add(new PassageProcessor(sentences, rules, embedding.getIfAvailable()));
        // #181: Dokumentnavigation aus den Embeddings je Absatz, ohne sie nur die Überschriften; Themenabschnitte
        // benennt das Chat-Modell (ADR 0020)
        EnrichmentStore enrichmentStore = store.getIfAvailable(InMemoryEnrichmentStore::new);
        processors.add(new NavigationProcessor(new DocumentNavigationBuilder(), embedding.getIfAvailable(),
                enrichmentStore::find, labels.getIfAvailable()));
        // ADR 0021: rechnet nie selbst, bestimmt nur, wann eine Bildanalyse gilt (Modellwechsel macht sie alt)
        processors.add(new ImageProcessor(describer::getIfAvailable));
        // #108: Satzanalyse über das Chat-Modell, nur eingeschaltet (AiConfiguration), im Hintergrund
        statements.ifAvailable(processors::add);
        return new Enrichments(projector(), processors,
                enrichmentStore, Clock.systemUTC(), background.getIfAvailable());
    }

    /** Satzgrenzen für Absätze und ihre Embeddings: das deutsche OpenNLP-Modell, ohne es die Satzregeln (ADR 0020). */
    @Bean
    SentenceSegmenter sentenceSegmenter() {
        return sentences(KnowledgeTextSegmentation.defaults());
    }

    static SentenceSegmenter sentences(TextSegmentation rules) {
        return OpenNlpSentenceSegmenter.german(SentenceSegmenter.splitter(rules));
    }

    /** Bildanalyse auf Abruf (ADR 0021, #362). */
    @Bean
    PageImageAnalysis pageImageAnalysis(Assets assets, Enrichments enrichments) {
        return new PageImageAnalysis(assets, enrichments, Clock.systemUTC());
    }

    /** Dokumentnavigation einer Seite für {@code GET …/navigation} (ADR 0020). */
    @Bean
    NavigationQueries navigationQueries(PageQueries pages, Enrichments enrichments) {
        return new NavigationQueries(pages, enrichments, projector(), new DocumentNavigationBuilder());
    }

    /** Semantische Absätze einer Seite für {@code GET …/passages} (ADR 0020). */
    @Bean
    PassageQueries passageQueries(PageQueries pages, Enrichments enrichments) {
        return new PassageQueries(pages, enrichments, projector());
    }

    /**
     * Auch lokale Dateien und Quellen über discover/load (#187) bekommen Anreicherungen und Embeddings, wie im
     * Volltextindex.
     */
    private static PageTextProjector projector() {
        return PageTextProjector.firstOf(List.of(new ConfluenceStorageTextProjector(),
                new LocalFilesTextProjector(), new KnowledgeDocumentTextProjector()));
    }

    @Bean
    EnrichmentsAfterSync enrichmentsAfterSync(Enrichments enrichments, ObjectProvider<SnapshotRepository> snapshots,
                                              ObjectProvider<Jobs> jobs, ObjectProvider<PageTextIndexing> texts,
                                              ObjectProvider<AcronymRelationRule> acronymRule) {
        // Eigener Auftrag ENRICHMENT: Abgleich und Export warten nicht auf die Anreicherung; er hält auch den
        // Volltextindex nach (#180) und danach die Regelvorschläge der Begriffsbeziehungen (#120).
        AcronymRelationRule rule = acronymRule.getIfAvailable();
        List<Consumer<String>> followUps = rule == null ? List.of() : List.of(rule::refresh);
        return new EnrichmentsAfterSync(enrichments, snapshots.getIfAvailable(), jobs.getIfAvailable(),
                texts.getIfAvailable(), followUps);
    }
    /**
     * Ergebnisse lokaler Prozessoren einer älteren Version (etwa nach geänderter Akronymerkennung) rechnet der Dienst
     * nach dem Start einmal aus dem Cache neu, gedrosselt und ohne den Start aufzuhalten; die Prozessoren über den
     * KI-Dienst bleiben bei der Neuberechnung nach Freigabe.
     */
    @Bean(destroyMethod = "close")
    VersionRecomputation versionRecomputation(Enrichments enrichments, ObjectProvider<LucenePageCache> cache) {
        LucenePageCache pages = cache.getIfAvailable();
        return new VersionRecomputation(enrichments,
                sourceId -> pages == null ? Collections.emptyIterator() : pages.snapshots(sourceId));
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> versionReconciliationOnStart(VersionRecomputation versions,
                                                                           ConfiguredSourceCatalog catalog) {
        return event -> versions.reconcileOnStart(
                catalog.configs().stream().map(SourceSettingsConfig::id).toList());
    }
}

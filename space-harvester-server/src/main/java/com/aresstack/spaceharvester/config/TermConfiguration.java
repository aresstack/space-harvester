package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.DocumentEnrichments;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.glossary.ApprovedGlossary;
import com.aresstack.spaceharvester.application.glossary.Glossary;
import com.aresstack.spaceharvester.application.glossary.GlossaryClassification;
import com.aresstack.spaceharvester.application.terms.AcronymExpansionModel;
import com.aresstack.spaceharvester.application.terms.AcronymRelationRule;
import com.aresstack.spaceharvester.application.terms.AcronymResolution;
import com.aresstack.spaceharvester.application.terms.SynonymCandidates;
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.application.terms.SynonymJudgmentModel;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.persistence.files.FileGlossaryStore;
import com.aresstack.spaceharvester.persistence.files.FileResolutionCache;
import com.aresstack.spaceharvester.persistence.files.FileSynonymVectorCache;
import com.aresstack.spaceharvester.persistence.files.FileTermRelationStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Begriffsindex (#119, ADR 0019): Abfragen über die Begriffsdokumente derselben Ablage, in die die Anreicherungen
 * schreiben. Kopfdaten liest er beim Lucene-Cache ohne Payload aus der Snapshot-Ablage, sonst aus dem Seitencache.
 * Begriffsbeziehungen (#120) liegen als Dateien unter {@code space-harvester.term-relations.directory} (Standard
 * {@code data/term-relations}), außerhalb des Caches.
 */
@Configuration(proxyBeanMethods = false)
class TermConfiguration {

    /**
     * Derselbe Lesetext wie die Anreicherung (Bean aus {@link AssetConfiguration}), sonst passt der Texthash der
     * Fundstellen nie.
     */
    private final PageTextProjector projector;

    TermConfiguration(PageTextProjector projector) {
        this.projector = projector;
    }

    private static final TermIndex EMPTY = (filter, from, inclusive, max) -> List.of();

    @Bean
    TermRelations termRelations(SourceCatalog catalog, AccessGuard access,
                                @Value("${space-harvester.term-relations.directory:data/term-relations}")
                                Path directory,
                                ObjectProvider<SnapshotRepository> repository, ObjectProvider<PageCacheReader> reader,
                                Enrichments enrichments, SentenceSegmenter sentences) {
        // BG1: Beziehungen je Seitenmenge über denselben Begriffsindex wie TermQueries.
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        return new TermRelations(catalog, new FileTermRelationStore(directory), access,
                headers(repository.getIfAvailable(), reader.getIfAvailable()), Clock.systemUTC(), index,
                termsVersion(sentences));
    }

    @Bean
    AcronymRelationRule acronymRelationRule(SourceCatalog catalog, AccessGuard access, Enrichments enrichments,
                                            TermRelations relations, ObjectProvider<SnapshotRepository> repository,
                                            ObjectProvider<PageCacheReader> reader, SentenceSegmenter sentences) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        return new AcronymRelationRule(catalog, index, access, headers(repository.getIfAvailable(),
                reader.getIfAvailable()), relations, termsVersion(sentences), Clock.systemUTC());
    }

    /** Ergebnis-Cache der KI-Akronymauflösung neben der Beziehungsablage; auch „Daten bereinigen“ leert ihn. */
    @Bean
    FileResolutionCache acronymResolutionCache(
            @Value("${space-harvester.term-relations.directory:data/term-relations}") Path directory) {
        return new FileResolutionCache(directory);
    }

    @Bean
    AcronymResolution acronymResolution(SourceCatalog catalog, AccessGuard access, Enrichments enrichments,
                                        TermRelations relations, Jobs jobs,
                                        @Qualifier("acronymResolutionCache") FileResolutionCache resolutions,
                                        ObjectProvider<AcronymExpansionModel> model,
                                        ObjectProvider<SnapshotRepository> repository,
                                        ObjectProvider<PageCacheReader> reader, SentenceSegmenter sentences) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        SnapshotRepository snapshots = repository.getIfAvailable();
        PageCacheReader cache = reader.getIfAvailable();
        Function<PageKey, Optional<PageSnapshot>> payloads = payloads(snapshots, cache);
        return new AcronymResolution(catalog, index, access, headers(snapshots, cache),
                key -> payloads.apply(key).flatMap(projector::project), relations,
                model.getIfAvailable(() -> AcronymExpansionModel.NONE), resolutions, jobs,
                termsVersion(sentences),
                Clock.systemUTC());
    }

    /**
     * Fachglossar als Nutzerdaten unter {@code space-harvester.glossary.directory}, außerhalb des Caches; mitgelieferte
     * geprüfte Glossare übernimmt es beim Start ({@link Glossary#adopt}).
     */
    @Bean
    Glossary glossary(SourceCatalog catalog, AccessGuard access,
                      @Value("${space-harvester.glossary.directory:data/glossary}") Path directory,
                      ObjectProvider<SnapshotRepository> repository, ObjectProvider<PageCacheReader> reader,
                      LiveConfiguration live) {
        Glossary glossary = new Glossary(catalog, new FileGlossaryStore(directory), access,
                headers(repository.getIfAvailable(), reader.getIfAvailable()), Clock.systemUTC());
        // Geprüfte Glossare beim Start übernehmen, nur mit space-harvester.terms.glossary.approved (Standard an).
        if (live.current().terms().glossaryApproved()) {
            ApprovedGlossary.bundled().forEach(glossary::adopt);
        }
        return glossary;
    }

    /** Suchmethode „Nur Fachbegriffe“ (K5b): Glossar und Synonymbeziehungen, nichts gespeichert. */
    @Bean
    com.aresstack.spaceharvester.application.glossary.TechnicalTerms technicalTerms(Glossary glossary,
                                                                                    TermRelations relations) {
        return new com.aresstack.spaceharvester.application.glossary.TechnicalTerms(glossary, relations);
    }

    /** „Fachbegriffe ermitteln“: dasselbe Chat-Modell wie die Akronymauflösung, nur auf Knopfdruck. */
    @Bean
    GlossaryClassification glossaryClassification(SourceCatalog catalog, AccessGuard access,
                                                  Enrichments enrichments, Glossary glossary, Jobs jobs,
                                                  ObjectProvider<AcronymExpansionModel> model,
                                                  ObjectProvider<SnapshotRepository> repository,
                                                  ObjectProvider<PageCacheReader> reader,
                                                  SentenceSegmenter sentences, ObjectProvider<DocumentEnrichments> documents,
                                                  @Qualifier("glossaryResolutionCache")
                                                  FileResolutionCache glossaryCache) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        SnapshotRepository snapshots = repository.getIfAvailable();
        PageCacheReader cache = reader.getIfAvailable();
        Function<PageKey, Optional<PageSnapshot>> payloads = payloads(snapshots, cache);
        return new GlossaryClassification(catalog, index, access, headers(snapshots, cache),
                key -> payloads.apply(key).flatMap(projector::project), glossary,
                model.getIfAvailable(() -> AcronymExpansionModel.NONE), glossaryCache, jobs,
                termsVersion(sentences), GlossaryClassification.Budget.DEFAULT, Clock.systemUTC());
    }

    /** Ergebnis-Cache der Glossar-Modellantworten (D4) im Glossarordner; „Daten bereinigen“ leert ihn. */
    @Bean
    FileResolutionCache glossaryResolutionCache(
            @Value("${space-harvester.glossary.directory:data/glossary}") Path directory) {
        return new FileResolutionCache(directory.resolve(GlossaryClassification.CACHE_DIRECTORY));
    }

    /** Ergebnis-Cache der Synonymerkennung (#336) neben dem der Akronymauflösung; „Daten bereinigen“ leert ihn. */
    @Bean
    FileResolutionCache synonymResolutionCache(
            @Value("${space-harvester.term-relations.directory:data/term-relations}") Path directory) {
        return new FileResolutionCache(directory.resolve("synonyms"));
    }

    /** Vektoren und Stand je Quelle der Synonymerkennung (SY2) neben ihrem Ergebnis-Cache. */
    @Bean
    FileSynonymVectorCache synonymVectorCache(
            @Value("${space-harvester.term-relations.directory:data/term-relations}") Path directory) {
        return new FileSynonymVectorCache(directory.resolve("synonyms"));
    }

    /**
     * Synonymerkennung auf Knopfdruck (#336): {@code POST /api/v1/term-relations/refresh} mit {@code kind=synonyms}.
     * Embedding-Modell und Chat-Modell folgen der wirksamen Konfiguration; Text geht nur aus freigegebenen Quellen an
     * den Dienst; das Budget kommt aus {@code space-harvester.terms.synonyms.*}.
     */
    @Bean
    SynonymDiscovery synonymDiscovery(SourceCatalog catalog, AccessGuard access, Enrichments enrichments,
                                      TermRelations relations, Jobs jobs,
                                      @Qualifier("synonymResolutionCache") FileResolutionCache resolutions,
                                      ObjectProvider<SynonymJudgmentModel> model,
                                      ObjectProvider<EmbeddingProcessor> embeddings, LiveConfiguration live,
                                      ObjectProvider<SnapshotRepository> repository,
                                      ObjectProvider<PageCacheReader> reader, SentenceSegmenter sentences,
                                      ObjectProvider<AiRelease> release, FileSynonymVectorCache vectors,
                                      ObjectProvider<Glossary> glossary) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        SnapshotRepository snapshots = repository.getIfAvailable();
        PageCacheReader cache = reader.getIfAvailable();
        Function<PageKey, Optional<PageSnapshot>> payloads = payloads(snapshots, cache);
        SynonymJudgmentModel judge = model.getIfAvailable(() -> SynonymJudgmentModel.NONE);
        EmbeddingProcessor processor = embeddings.getIfAvailable();
        SynonymCandidates candidates = new SynonymCandidates(catalog, index, headers(snapshots, cache),
                key -> payloads.apply(key).flatMap(projector::project), relations,
                () -> processor == null ? Optional.empty() : processor.currentProvider(), judge::mayProcess,
                termsVersion(sentences));
        candidates.vectors(vectors);
        // K3: Synonyme wahlweise nur aus bestätigten Fachbegriffen des Glossars.
        candidates.glossaryTerms(scope -> {
            Glossary terms = glossary.getIfAvailable();
            return terms == null ? java.util.Set.of() : terms.confirmedTerms(scope.relationScope());
        });
        SynonymDiscovery discovery = new SynonymDiscovery(catalog, candidates, access, relations, judge, resolutions,
                jobs, () -> live.current().terms().synonyms(), Clock.systemUTC());
        // ADR 0028: Textstellen von Seiten auf der Liste freigegebener Seiten gehen auch aus gesperrten Quellen hinaus.
        AiRelease pages = release.getIfAvailable();
        if (pages != null) {
            discovery.pageReleases(pages.pages()::contains);
            // SY1: Neue KI-Freigaben von Seiten lösen den automatischen Lauf ihrer Quelle aus.
            pages.pages().subscribe(discovery::releasesChanged);
        }
        return discovery;
    }

    private static Function<PageKey, Optional<PageSnapshot>> payloads(SnapshotRepository snapshots,
                                                                      PageCacheReader cache) {
        if (snapshots != null && (cache == null || cache instanceof LucenePageCacheReader)) {
            return snapshots::find;
        }
        if (cache != null) {
            return key -> cache.page(key).map(CachedPage::snapshot);
        }
        return key -> Optional.empty();
    }

    private static Function<PageKey, Optional<PageHeader>> headers(SnapshotRepository snapshots,
                                                                   PageCacheReader cache) {
        if (snapshots != null && (cache == null || cache instanceof LucenePageCacheReader)) {
            return snapshots::findHeader;
        }
        if (cache != null) {
            return key -> cache.page(key).map(CachedPage::header);
        }
        return key -> Optional.empty();
    }

    @Bean
    TermQueries termQueries(SourceCatalog catalog, AccessGuard access, Enrichments enrichments,
                            ObjectProvider<SnapshotRepository> repository, ObjectProvider<PageCacheReader> reader,
                            SentenceSegmenter sentences, ObjectProvider<DocumentEnrichments> documents,
                            com.aresstack.spaceharvester.application.query.QueryLimits queryLimits) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        SnapshotRepository snapshots = repository.getIfAvailable();
        PageCacheReader cache = reader.getIfAvailable();
        Function<PageKey, Optional<PageHeader>> headers;
        Function<PageKey, Optional<PageSnapshot>> payloads;
        // Der Lucene-Cache liest Kopfdaten ohne Payload; jeder andere Seitencache (etwa im Test) ist die Wahrheit.
        if (snapshots != null && (cache == null || cache instanceof LucenePageCacheReader)) {
            headers = snapshots::findHeader;
            payloads = snapshots::find;
        } else if (cache != null) {
            headers = key -> cache.page(key).map(CachedPage::header);
            payloads = key -> cache.page(key).map(CachedPage::snapshot);
        } else {
            headers = key -> Optional.empty();
            payloads = key -> Optional.empty();
        }
        // ADR 0026, AP9b: Snippets aus Anhängen nur gegen die am Stand vermerkte Extraktion.
        return new TermQueries(catalog, index, access, headers, payloads, projector, termsVersion(sentences),
                (document, section, inputSha256, extraction) -> {
                    DocumentEnrichments found = documents.getIfAvailable();
                    return found == null ? Optional.empty()
                            : found.sectionText(document, section, inputSha256, extraction);
                }, queryLimits);
    }

    /** Wie {@code terms} in der Anreicherung (EnrichmentConfiguration): dieselben Satzgrenzen, dieselbe Version. */
    private static String termsVersion(SentenceSegmenter sentences) {
        return new TermProcessor(new AcronymProcessor(sentences)).version();
    }
}

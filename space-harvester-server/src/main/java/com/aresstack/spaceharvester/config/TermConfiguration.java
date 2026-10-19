package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.AcronymExpansionModel;
import com.aresstack.spaceharvester.application.terms.AcronymRelationRule;
import com.aresstack.spaceharvester.application.terms.AcronymResolution;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.persistence.files.FileTermRelationStore;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final TermIndex EMPTY = (filter, from, inclusive, max) -> List.of();

    @Bean
    TermRelations termRelations(SourceCatalog catalog, AccessGuard access,
                                @Value("${space-harvester.term-relations.directory:data/term-relations}")
                                Path directory,
                                ObjectProvider<SnapshotRepository> repository, ObjectProvider<PageCacheReader> reader) {
        return new TermRelations(catalog, new FileTermRelationStore(directory), access,
                headers(repository.getIfAvailable(), reader.getIfAvailable()), Clock.systemUTC());
    }

    @Bean
    AcronymRelationRule acronymRelationRule(SourceCatalog catalog, AccessGuard access, Enrichments enrichments,
                                            TermRelations relations, ObjectProvider<SnapshotRepository> repository,
                                            ObjectProvider<PageCacheReader> reader) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        return new AcronymRelationRule(catalog, index, access, headers(repository.getIfAvailable(),
                reader.getIfAvailable()), relations, new TermProcessor().version(), Clock.systemUTC());
    }

    @Bean
    AcronymResolution acronymResolution(SourceCatalog catalog, AccessGuard access, Enrichments enrichments,
                                        TermRelations relations, Jobs jobs,
                                        ObjectProvider<AcronymExpansionModel> model,
                                        ObjectProvider<SnapshotRepository> repository,
                                        ObjectProvider<PageCacheReader> reader) {
        TermIndex index = enrichments.store() instanceof TermIndex terms ? terms : EMPTY;
        SnapshotRepository snapshots = repository.getIfAvailable();
        PageCacheReader cache = reader.getIfAvailable();
        Function<PageKey, Optional<PageSnapshot>> payloads = payloads(snapshots, cache);
        PageTextProjector projector = projector();
        return new AcronymResolution(catalog, index, access, headers(snapshots, cache),
                key -> payloads.apply(key).flatMap(projector::project), relations,
                model.getIfAvailable(() -> AcronymExpansionModel.NONE), jobs, new TermProcessor().version(),
                Clock.systemUTC());
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

    private static PageTextProjector projector() {
        // Wie die Anreicherung (EnrichmentConfiguration), sonst passt der Texthash der Fundstellen nie.
        return PageTextProjector.firstOf(List.of(new ConfluenceStorageTextProjector(),
                new LocalFilesTextProjector(), new KnowledgeDocumentTextProjector()));
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
                            ObjectProvider<SnapshotRepository> repository, ObjectProvider<PageCacheReader> reader) {
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
        return new TermQueries(catalog, index, access, headers, payloads, projector(),
                new TermProcessor().version());
    }
}

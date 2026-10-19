package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCacheReader;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import com.aresstack.spaceharvester.source.confluence.ConfluenceStorageTextProjector;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Begriffsindex (#119, ADR 0019): Abfragen über die Begriffsdokumente derselben Ablage, in die die Anreicherungen
 * schreiben. Kopfdaten liest er beim Lucene-Cache ohne Payload aus der Snapshot-Ablage, sonst aus dem Seitencache.
 */
@Configuration(proxyBeanMethods = false)
class TermConfiguration {

    private static final TermIndex EMPTY = (filter, from, inclusive, max) -> List.of();

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
        PageTextProjector projector = PageTextProjector.firstOf(List.of(new ConfluenceStorageTextProjector(),
                new LocalFilesTextProjector()));
        return new TermQueries(catalog, index, access, headers, payloads, projector, new TermProcessor().version());
    }
}

package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.ProvisioningSearch;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die cachegestützte Query-API (#27) und die Suche (#28). Solange kein {@link PageCacheReader} bzw.
 * {@link PageSearchIndex} als Bean existiert (der Lucene-Cache aus #26 liefert sie), antworten ihre Routen mit
 * {@code cache-unavailable}.
 */
@Configuration(proxyBeanMethods = false)
class QueryConfiguration {

    @Bean
    PageQueries pageQueries(SourceCatalog catalog, ObjectProvider<PageCacheReader> cache, AccessGuard access,
                            PageFreshness freshness, ObjectProvider<PageCacheFill> fill,
                            @Value("${space-harvester.query.max-tree-nodes:50000}") int maxTreeNodes) {
        return new PageQueries(catalog, cache.getIfAvailable(PageCacheReader::unavailable), access, freshness,
                fill.getIfAvailable(() -> PageCacheFill.NONE), maxTreeNodes);
    }

    @Bean
    PageSearch pageSearch(SourceCatalog catalog, ObjectProvider<PageSearchIndex> index, AccessGuard access) {
        return new PageSearch(catalog, index.getIfAvailable(PageSearchIndex::unavailable), access);
    }

    /** Die Suche der REST-API: mit Suchtext live in den Quellen, Treffer nachgeladen und indexiert (ADR 0010). */
    @Bean
    ProvisioningSearch provisioningSearch(SourceCatalog catalog, ObjectProvider<PageSearchIndex> index,
                                          AccessGuard access, PageQueries pages) {
        return new ProvisioningSearch(catalog, index.getIfAvailable(PageSearchIndex::unavailable), access, pages);
    }

    /**
     * {@code sort=relevance} (#89): semantisch nur, wenn ein Embedding-Modell eingerichtet ist und die Anreicherungen im
     * Lucene-Cache liegen (der zugleich Ablage und Vektorsuche ist); sonst nur Titeltreffer.
     */
    @Bean
    RelevanceSearch relevanceSearch(SourceCatalog catalog, ObjectProvider<PageSearchIndex> index, AccessGuard access,
                                    ObjectProvider<EnrichmentStore> store, ObjectProvider<EmbeddingIndex> vectors,
                                    ObjectProvider<EmbeddingProcessor> embedding) {
        return new RelevanceSearch(catalog, index.getIfAvailable(PageSearchIndex::unavailable), access,
                store.getIfUnique(), vectors.getIfUnique(), embedding.getIfUnique());
    }
}

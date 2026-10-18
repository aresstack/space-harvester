package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.SearchHitPrefetch;
import com.aresstack.spaceharvester.application.ranking.PageRenewal;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** Adressauflösung über alle Quellen (#178). */
    @Bean
    PageAddresses pageAddresses(SourceCatalog catalog, AccessGuard access, PageQueries pages) {
        return new PageAddresses(catalog, access, pages);
    }

    /** Lese-Ansicht (#176); abgelegt neben dem Snapshot in der Ablage der Anreicherungen. */
    @Bean
    ReadingViews readingViews(SourceCatalog catalog, PageQueries pages, AccessGuard access,
                              ObjectProvider<EnrichmentStore> store) {
        return new ReadingViews(catalog, pages, access, new ReadingViewStore(store.getIfAvailable(
                InMemoryEnrichmentStore::new)), Clock.systemUTC());
    }

    /** Live-Navigation (#177); der Cache nennt nur den Space von Verweiszielen. */
    @Bean
    PageNavigation pageNavigation(SourceCatalog catalog, ObjectProvider<PageCacheReader> cache, AccessGuard access) {
        return new PageNavigation(catalog, cache.getIfAvailable(), access);
    }

    /** Die Titel- und Metadatensuche im Index, etwa für die Confluence-kompatible Fassade. */
    @Bean
    PageSearch pageSearch(SourceCatalog catalog, ObjectProvider<PageSearchIndex> index, AccessGuard access) {
        return new PageSearch(catalog, index.getIfAvailable(PageSearchIndex::unavailable), access);
    }

    /**
     * Die Schlagwortsuche über den Cache (ADR 0013): die Quellen finden, gefundene Seiten werden geladen, der
     * Volltextindex sucht; ohne Cache die Treffer der Quellen selbst.
     */
    @Bean
    KeywordSearch keywordSearch(SourceCatalog catalog, AccessGuard access, ObjectProvider<PageTextIndex> texts,
                                SearchHitPrefetch prefetch) {
        return new KeywordSearch(catalog, access, texts.getIfAvailable(), prefetch);
    }

    /**
     * Lädt gefundene Seiten der Schlagwortsuche in den Cache (ADR 0013): vier Abrufe zugleich, höchstens 200 wartende;
     * ohne Cache nichts. Der Hintergrund ist bewusst keine eigene Bean, damit er keinen Executor von Spring
     * verdrängt.
     */
    @Bean(destroyMethod = "close")
    SearchHitPrefetch searchHitPrefetch(ObjectProvider<LucenePageCache> cache, PageQueries pages) {
        LucenePageCache available = cache.getIfAvailable();
        if (available == null) {
            return SearchHitPrefetch.disabled();
        }
        return new SearchHitPrefetch(key -> available.findHeader(key).map(header -> header.metadata().version()),
                (caller, key) -> pages.page(caller, key.sourceId(), key.pageId(), false),
                SearchHitPrefetch.background(4, 200));
    }

    /**
     * {@code sort=relevance} (#89, ADR 0011): nur mit Embedding-Dienst und Anreicherungen im Lucene-Cache (der zugleich
     * Ablage und Vektorsuche ist); sonst {@code semantic-search-unavailable}. Mit dem Hintergrund
     * {@code relevanceRenewals} prüft die Suche jeden Treffer live und lädt neuere Versionen nach. Mit dem
     * Volltextindex über den Cache sieht ein Reranker den Lesetext; Kandidaten sind nur Vektortreffer, ohne Fusion
     * (ADR 0013). Mit {@code rerank-model} ordnet danach der Cross-Encoder des Dienstes um.
     */
    @Bean
    RelevanceSearch relevanceSearch(SourceCatalog catalog, ObjectProvider<PageSearchIndex> index, AccessGuard access,
                                    ObjectProvider<EnrichmentStore> store, ObjectProvider<EmbeddingIndex> vectors,
                                    ObjectProvider<EmbeddingProcessor> embedding, PageQueries pages,
                                    @Qualifier("relevanceRenewals") ObjectProvider<ExecutorService> renewals,
                                    ObjectProvider<PageTextIndex> texts, ObjectProvider<SearchReranker> reranker) {
        ExecutorService background = renewals.getIfAvailable();
        return new RelevanceSearch(catalog, index.getIfAvailable(PageSearchIndex::unavailable), access,
                store.getIfUnique(), vectors.getIfUnique(), embedding.getIfUnique(),
                background == null ? null : new PageRenewal(pages, background),
                reranker.getIfUnique(() -> SearchReranker.NONE),
                texts.getIfUnique());
    }
}

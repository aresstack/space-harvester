package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.api.ProgressiveSearches;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.ExcerptPriority;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageCacheFill;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import com.aresstack.spaceharvester.application.query.SearchHitPrefetch;
import com.aresstack.spaceharvester.application.query.progressive.AiQueryExpansion;
import com.aresstack.spaceharvester.application.query.progressive.HitScorer;
import com.aresstack.spaceharvester.application.query.progressive.RuleBasedExpansion;
import com.aresstack.spaceharvester.application.query.progressive.SearchOrchestrator;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.query.progressive.VariantSearch;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.application.ranking.PageRenewal;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.ranking.SwappableReranker;
import com.aresstack.spaceharvester.application.ranking.SemanticPreparation;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.facts.FactStore;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.facts.PageFacts;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die cachegestützte Query-API (#27), die Suche (#28) und die progressive Suche (ADR 0017). Solange kein
 * {@link PageCacheReader} bzw. {@link PageSearchIndex} als Bean existiert (der Lucene-Cache aus #26 liefert sie),
 * antworten ihre Routen mit {@code cache-unavailable}.
 */
@Configuration(proxyBeanMethods = false)
class QueryConfiguration {

    /**
     * Seitenabruf mit Cache (ADR 0010). {@code space-harvester.cache.max-age} (Standard {@code 0s}) ist das Zeitfenster,
     * in dem eine Seite ohne Rückfrage bei der Quelle gilt; {@code space-harvester.cache.version-check} (Standard
     * {@code true}) prüft danach die Version, {@code false} ruft die Seite neu ab (ADR 0013). Beides gilt je Quelle
     * ({@code sync.sources.<Quelle>.*}) und folgt der Konfiguration zur Laufzeit beim nächsten Abruf.
     */
    @Bean
    PageQueries pageQueries(SourceCatalog catalog, ObjectProvider<PageCacheReader> cache, AccessGuard access,
                            PageFreshness freshness, ObjectProvider<PageCacheFill> fill,
                            @Value("${space-harvester.query.max-tree-nodes:50000}") int maxTreeNodes,
                            LiveConfiguration live) {
        return new PageQueries(catalog, cache.getIfAvailable(PageCacheReader::unavailable), access, freshness,
                fill.getIfAvailable(() -> PageCacheFill.NONE), maxTreeNodes, live::freshnessFor, Clock.systemUTC());
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
     * Volltextindex sucht; ohne Cache die Treffer der Quellen selbst. {@code space-harvester.search.discovery.max-queries}
     * (Standard 4, 1 bis 8) begrenzt die Anfragen je Quelle, die aus einer längeren Frage entstehen; 1 schickt den
     * Suchtext unverändert. {@code space-harvester.search.excerpt-ranking} (Standard {@code true}) lässt mit
     * {@code rerank-model} den Cross-Encoder die Funde nach Titel und Auszug bewerten, um die vielversprechendsten zuerst
     * zu laden (nur Quellen in {@code external-processing-sources}). {@code space-harvester.search.loads} (Standard
     * {@value KeywordSearch#DEFAULT_LOADS}, alle) begrenzt, wie viele Funde je Stapel in den Cache laden, die besten
     * zuerst; alle Funde kommen sofort als vorläufige Treffer mit Titel und Auszug ({@code found}).
     */
    @Bean
    KeywordSearch keywordSearch(SourceCatalog catalog, AccessGuard access, ObjectProvider<PageTextIndex> texts,
                                SearchHitPrefetch prefetch, ObjectProvider<SearchReranker> reranker,
                                @Value("${space-harvester.search.discovery.max-queries:4}") int maxQueries,
                                @Value("${space-harvester.search.excerpt-ranking:true}") boolean excerptRanking,
                                @Value("${space-harvester.search.loads:" + KeywordSearch.DEFAULT_LOADS + "}") int loads,
                                SearchRunLog runs) {
        return new KeywordSearch(catalog, access, texts.getIfAvailable(), prefetch, maxQueries,
                excerpts(reranker.getIfUnique(), excerptRanking), loads, runs);
    }

    /** Der Cross-Encoder für Titel und Auszug, wenn {@code rerank-model} gesetzt ist; sonst keine Bewertung. */
    private static ExcerptPriority excerpts(SearchReranker current, boolean excerptRanking) {
        if (!excerptRanking) {
            return ExcerptPriority.off();
        }
        if (current instanceof SwappableReranker live) {
            // Folgt dem Reranker der wirksamen Konfiguration (ADR 0015).
            return ExcerptPriority.following(() -> live.current() instanceof ModelReranker cross
                    ? cross.excerptPriority() : ExcerptPriority.off());
        }
        return current instanceof ModelReranker cross ? cross.excerptPriority() : ExcerptPriority.off();
    }

    /**
     * Progressive Suche (ADR 0017) wie {@code Harvester.progressiveSearch()}: die Schlagwortsuche mit ihren
     * Rechteprüfungen je Suchanfrage, Varianten vom Chat-Modell der wirksamen Konfiguration mit den Regeln als Rückfall,
     * bewertet gegen die ursprüngliche Anfrage vom Cross-Encoder ({@code rerank-model}, nur Quellen in
     * {@code external-processing-sources}; ohne ihn keine Bewertung). Schließt vor der Schlagwortsuche und dem Cache:
     * bricht laufende Suchen ab und wartet auf ihre Suchanfragen, ohne sie zu unterbrechen.
     */
    @Bean(destroyMethod = "close")
    SearchOrchestrator searchOrchestrator(KeywordSearch keywords, ObjectProvider<SearchReranker> reranker,
                                          QueryVariantModels models,
                                          @Value("${space-harvester.search.excerpt-ranking:true}")
                                          boolean excerptRanking, SearchRunLog runs) {
        RuleBasedExpansion rules = new RuleBasedExpansion();
        return new SearchOrchestrator(VariantSearch.of(keywords),
                new AiQueryExpansion(models, rules, AiQueryExpansion.DEFAULT_TIMEOUT), rules,
                HitScorer.of(excerpts(reranker.getIfUnique(), excerptRanking)), runs);
    }

    /**
     * Die progressiven Suchen der REST-API (ADR 0017), je Aufrufer; Standardprofil mit Varianten vom Chat-Modell,
     * wenn die wirksame Konfiguration eines hat, sonst nach Regeln.
     */
    @Bean(destroyMethod = "close")
    ProgressiveSearches progressiveSearches(SearchOrchestrator orchestrator, QueryVariantModels models) {
        return new ProgressiveSearches(orchestrator, () -> SearchProfile.defaults().withExpansion(
                models.get().isPresent() ? SearchProfile.Expansion.AI : SearchProfile.Expansion.RULES));
    }

    /**
     * Lädt gefundene Seiten der Schlagwortsuche in den Cache (ADR 0013): vier Abrufe zugleich, höchstens 200 wartende;
     * ohne Cache nichts. Technische Clients warten nicht darauf ({@code progress}), delegierte Benutzer höchstens
     * {@link SearchHitPrefetch#DEFAULT_WAIT}, weil ihre Anmeldung den Aufruf nicht überdauert. Der Hintergrund ist bewusst keine eigene Bean, damit er keinen Executor von Spring
     * verdrängt.
     */
    @Bean(destroyMethod = "close")
    SearchHitPrefetch searchHitPrefetch(ObjectProvider<LucenePageCache> cache,
                                        ObjectProvider<PageTextIndexing> texts, PageQueries pages) {
        LucenePageCache available = cache.getIfAvailable();
        PageTextIndexing indexing = texts.getIfAvailable();
        if (available == null || indexing == null) {
            return SearchHitPrefetch.disabled();
        }
        return new SearchHitPrefetch(key -> indexing.cachedVersion(key, available::findHeader, available::find),
                key -> available.findHeader(key).map(PageHeader::metadata),
                (caller, key) -> pages.page(caller, key.sourceId(), key.pageId(), false),
                SearchHitPrefetch.background(4, 200), SearchHitPrefetch.DEFAULT_WAIT, Duration.ZERO);
    }

    /**
     * {@code sort=relevance} (#89, ADR 0011): nur mit Embedding-Dienst und Anreicherungen im Lucene-Cache (der zugleich
     * Ablage und Vektorsuche ist); sonst {@code semantic-search-unavailable}. Mit dem Hintergrund
     * {@code relevanceRenewals} prüft die Suche jeden Treffer live und lädt neuere Versionen nach. Mit dem
     * Volltextindex über den Cache sieht ein Reranker den Lesetext; Kandidaten sind nur Vektortreffer, ohne Fusion
     * (ADR 0013). Vorher finden die Quellen, die gefundenen Seiten werden geladen und ihre fehlenden Embeddings
     * berechnet (ADR 0013, Punkt 3). Mit {@code rerank-model} ordnet danach der Cross-Encoder des Dienstes um.
     */
    @Bean
    RelevanceSearch relevanceSearch(SourceCatalog catalog, ObjectProvider<PageSearchIndex> index, AccessGuard access,
                                    ObjectProvider<EnrichmentStore> store, ObjectProvider<EmbeddingIndex> vectors,
                                    ObjectProvider<EmbeddingProcessor> embedding, PageQueries pages,
                                    @Qualifier("relevanceRenewals") ObjectProvider<ExecutorService> renewals,
                                    ObjectProvider<PageTextIndex> texts, ObjectProvider<SearchReranker> reranker,
                                    KeywordSearch keywords, ObjectProvider<PageCacheReader> cache,
                                    ObjectProvider<Enrichments> enrichments, SearchRunLog runs) {
        ExecutorService background = renewals.getIfAvailable();
        EmbeddingProcessor processor = embedding.getIfUnique();
        PageCacheReader reader = cache.getIfAvailable();
        Enrichments available = enrichments.getIfAvailable();
        // ADR 0013, Punkt 3: erst finden, laden und fehlende Embeddings berechnen, dann semantisch suchen.
        SemanticPreparation preparation = processor == null || reader == null || available == null
                || !keywords.searchesTheCache() ? null
                : new SemanticPreparation(keywords, key -> reader.page(key).map(CachedPage::snapshot), available,
                processor.id(), SemanticPreparation.DEFAULT_WAIT);
        return new RelevanceSearch(catalog, index.getIfAvailable(PageSearchIndex::unavailable), access,
                store.getIfUnique(), vectors.getIfUnique(), processor,
                background == null ? null : new PageRenewal(pages, background),
                reranker.getIfUnique(() -> SearchReranker.NONE),
                texts.getIfUnique(), preparation, runs);
    }

    /**
     * Fakten je Seite aus den Anreicherungen (#182), aus derselben Ablage wie die Anreicherungen (Lucene-Cache oder
     * Speicher). Rechte je Seite an ihrem Space im Cache.
     */
    @Bean
    PageFacts pageFacts(SourceCatalog catalog, AccessGuard access, PageQueries pages,
                        ObjectProvider<Enrichments> enrichments, ObjectProvider<FactStore> store,
                        ObjectProvider<LucenePageCache> cache, ObjectProvider<PageCacheReader> reader) {
        Enrichments available = enrichments.getIfAvailable();
        FactStore facts = (available == null ? Optional.<FactStore>empty() : available.facts())
                .orElseGet(() -> store.getIfUnique(() -> NO_FACTS));
        LucenePageCache lucene = cache.getIfAvailable();
        PageCacheReader any = reader.getIfAvailable();
        Function<PageKey, Optional<PageHeader>> headers = lucene != null ? lucene::findHeader
                : any != null ? key -> any.page(key).map(CachedPage::header) : key -> Optional.empty();
        return new PageFacts(catalog, access, pages, available, facts, headers);
    }

    /** Ohne Ablage: keine Fakten. */
    private static final FactStore NO_FACTS = new FactStore() {
        @Override
        public List<MetadataFact> factsOf(PageKey page) {
            return List.of();
        }

        @Override
        public List<MetadataFact> facts(Query query, String after, int limit) {
            return List.of();
        }
    };
}

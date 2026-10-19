package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.api.ProgressiveSearches;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.CandidateSelectionStrategy;
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
import com.aresstack.spaceharvester.application.ranking.SemanticVariantSearch;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.ReadingViews;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
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
     * Der technische Aufrufer der Hintergrundfüllung (ADR 0016): eine Aktion des Servers, nie im Auftrag eines Clients
     * oder Benutzers. Erkannt wird er nur an genau dieser Instanz, nie an seinem Namen; der Name ist keine gültige
     * Client-ID.
     */
    static final AccessContext BACKGROUND = AccessContext.technicalClient("space-harvester background (intern)");

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
     * {@code space-harvester.search.candidate-selection} ({@code order}, {@code top-n}, {@code adaptive}; ADR 0016)
     * wählt, welche Funde jetzt laden; ohne Angabe {@code adaptive}, mit gesetztem {@code search.loads} aber
     * {@code top-n} wie bisher.
     */
    @Bean
    KeywordSearch keywordSearch(SourceCatalog catalog, AccessGuard access, ObjectProvider<PageTextIndex> texts,
                                SearchHitPrefetch prefetch, ObjectProvider<SearchReranker> reranker,
                                @Value("${space-harvester.search.discovery.max-queries:4}") int maxQueries,
                                @Value("${space-harvester.search.excerpt-ranking:true}") boolean excerptRanking,
                                @Value("${space-harvester.search.loads:}") String loadsValue,
                                @Value("${space-harvester.search.candidate-selection:}") String selectionValue) {
        return new KeywordSearch(catalog, access, texts.getIfAvailable(), prefetch, maxQueries,
                excerpts(reranker.getIfUnique(), excerptRanking), selection(selectionValue, loadsValue));
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
     * {@code external-processing-sources}; ohne ihn keine Bewertung). Mit {@code ranking=semantic} läuft jede
     * Suchanfrage über die {@link RelevanceSearch} ({@link SemanticVariantSearch}, ADR 0013): Die Variante findet und
     * lädt nur, Treffer kommen aus den Vektoren mit der ursprünglichen Anfrage; ohne Embedding-Modell
     * {@code semantic-search-unavailable}. Schließt vor der Schlagwortsuche und dem Cache: bricht laufende Suchen ab und
     * wartet auf ihre Suchanfragen, ohne sie zu unterbrechen.
     */
    @Bean(destroyMethod = "close")
    SearchOrchestrator searchOrchestrator(KeywordSearch keywords, RelevanceSearch relevance,
                                          ObjectProvider<PageTextIndex> texts,
                                          ObjectProvider<SearchReranker> reranker, QueryVariantModels models,
                                          @Value("${space-harvester.search.excerpt-ranking:true}")
                                          boolean excerptRanking) {
        RuleBasedExpansion rules = new RuleBasedExpansion();
        return new SearchOrchestrator(VariantSearch.of(keywords), new SemanticVariantSearch(relevance,
                texts.getIfUnique()),
                new AiQueryExpansion(models, rules, AiQueryExpansion.DEFAULT_TIMEOUT), rules,
                HitScorer.of(excerpts(reranker.getIfUnique(), excerptRanking)));
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
     * Die Auswahl der Beschaffung aus {@code search.candidate-selection} und {@code search.loads}.
     *
     * @throws IllegalArgumentException bei unbekannter Strategie oder Obergrenze außerhalb 1 bis 100
     */
    static CandidateSelectionStrategy selection(String selectionValue, String loadsValue) {
        boolean loadsSet = loadsValue != null && !loadsValue.isBlank();
        int loads;
        try {
            loads = loadsSet ? Integer.parseInt(loadsValue.strip()) : KeywordSearch.DEFAULT_LOADS;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("space-harvester.search.loads muss eine Zahl sein");
        }
        if (loads < 1 || loads > KeywordSearch.MAX_LIMIT) {
            throw new IllegalArgumentException("space-harvester.search.loads muss zwischen 1 und "
                    + KeywordSearch.MAX_LIMIT + " liegen");
        }
        if (selectionValue == null || selectionValue.isBlank()) {
            return loadsSet ? CandidateSelectionStrategy.topN(loads)
                    : CandidateSelectionStrategy.of(CandidateSelectionStrategy.Kind.ADAPTIVE, loads);
        }
        return CandidateSelectionStrategy.of(CandidateSelectionStrategy.Kind.of(selectionValue), loads);
    }

    /**
     * Der Beschaffungsplaner (ADR 0016): eine Warteschlange für die Funde der Suche (vier zugleich, reserviert) und die
     * Hintergrundfüllung ({@code space-harvester.sync.background-parallelism}, Standard
     * {@value SyncSettings#DEFAULT_BACKGROUND_PARALLELISM}, zur Laufzeit änderbar; siehe
     * {@link AcquisitionConfiguration}), je Quelle höchstens vier, über denselben Beschaffungsweg {@link PageQueries#page}. Der Hintergrund lädt als Aktion
     * des Servers ({@link #BACKGROUND}) über einen eigenen {@link PageQueries} mit demselben Cache, derselben Ablage und
     * denselben Anreicherungen, dessen Rechte nur diesen Aufrufer kennen; kein Client erreicht ihn.
     */
    @Bean(destroyMethod = "close")
    AcquisitionScheduler acquisitionScheduler(SourceCatalog catalog, ObjectProvider<PageCacheReader> cache,
                                              PageQueries pages, PageFreshness freshness,
                                              ObjectProvider<PageCacheFill> fill, LiveConfiguration live,
                                              @Value("${space-harvester.query.max-tree-nodes:50000}") int maxTreeNodes) {
        PageQueries technical = new PageQueries(catalog, cache.getIfAvailable(PageCacheReader::unavailable),
                new AccessGuard((context, scope, kind) -> context == BACKGROUND ? AccessDecision.ALLOW
                        : AccessDecision.DENY), freshness, fill.getIfAvailable(() -> PageCacheFill.NONE), maxTreeNodes,
                live::freshnessFor, Clock.systemUTC());
        AcquisitionScheduler.Limits defaults = AcquisitionScheduler.Limits.DEFAULT;
        return new AcquisitionScheduler((caller, key, background) -> (caller == BACKGROUND ? technical : pages)
                .acquire(caller, key, background),
                new AcquisitionScheduler.Limits(defaults.foreground(), live.current().sync().backgroundParallelism(),
                        defaults.perSource(), defaults.capacity()), null, AcquisitionScheduler.DEFAULT_RESUME_DELAY);
    }

    /**
     * Lädt gefundene Seiten der Schlagwortsuche in den Cache (ADR 0013), als Vordergrund des
     * {@link AcquisitionScheduler}: vier Abrufe zugleich, höchstens 200 wartende; ohne Cache nichts. Technische
     * Clients warten nicht darauf ({@code progress}), delegierte Benutzer höchstens
     * {@link SearchHitPrefetch#DEFAULT_WAIT}, weil ihre Anmeldung den Aufruf nicht überdauert.
     */
    @Bean(destroyMethod = "close")
    SearchHitPrefetch searchHitPrefetch(ObjectProvider<LucenePageCache> cache,
                                        ObjectProvider<PageTextIndexing> texts, AcquisitionScheduler scheduler) {
        LucenePageCache available = cache.getIfAvailable();
        PageTextIndexing indexing = texts.getIfAvailable();
        if (available == null || indexing == null) {
            return SearchHitPrefetch.disabled();
        }
        return new SearchHitPrefetch(key -> indexing.cachedVersion(key, available::findHeader, available::find),
                key -> available.findHeader(key).map(PageHeader::metadata), scheduler, SearchHitPrefetch.DEFAULT_WAIT,
                Duration.ZERO);
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
                                    ObjectProvider<Enrichments> enrichments) {
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
                texts.getIfUnique(), preparation);
    }
}

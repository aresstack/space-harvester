package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.query.progressive.SearchExecutionPolicy;
import com.aresstack.spaceharvester.application.query.progressive.SearchProfile;
import com.aresstack.spaceharvester.application.query.progressive.VariantSearch;
import com.aresstack.spaceharvester.application.searchrun.SearchRunId;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Die Suchanfragen der progressiven Suche über die semantische Suche ({@link SearchProfile.Ranking#SEMANTIC}, ADR 0017
 * mit ADR 0013). Eine Variante findet nur: Ihre Funde in den Quellen werden mit den Rechten des Aufrufers in den Cache
 * geladen und eingebettet ({@link RelevanceSearch.Preparation}). Treffer liefert allein die Vektorsuche mit der
 * ursprünglichen Anfrage, mit allen Rechte- und Live-Prüfungen der {@link RelevanceSearch}. Ihre Bewertung ist die
 * Kosinusähnlichkeit zur ursprünglichen Anfrage, so sind die Treffer aller Varianten ohne Fusion vergleichbar.
 *
 * <p>Vorläufige Treffer aus der Suche der Quellen gibt es hier nicht, alles kommt aus dem Cache. Ausgeschlossene Spaces
 * werden weder geladen noch Kandidaten. Der Auszug ist der Lesetext um das ähnlichste Stück aus dem Volltextindex des Caches; die Seite hat
 * {@code content}-Freigabe, sonst wäre sie kein Kandidat der Vektorsuche.
 */
public final class SemanticVariantSearch implements VariantSearch {

    /** Längster Auszug je Treffer. */
    static final int MAX_SNIPPET_CHARS = 300;

    /**
     * Frist je Suchanfrage für ein Profil mit {@link SearchProfile.Ranking#SEMANTIC}: Finden, Laden und Embeddings
     * ({@link SemanticPreparation#DEFAULT_WAIT}), danach Vektorsuche und Live-Prüfung.
     */
    public static final Duration VARIANT_TIMEOUT = SemanticPreparation.DEFAULT_WAIT.plusSeconds(10);

    /** Zeitbudget einer semantischen progressiven Suche: Jede Suchanfrage lädt und bettet erst ein, bevor sie sucht. */
    public static final Duration TIME_BUDGET = Duration.ofSeconds(60);

    private final RelevanceSearch relevance;
    private final PageTextIndex texts;

    /** @param texts Volltextindex über den Cache für den Auszug oder {@code null}, dann ohne Auszug */
    public SemanticVariantSearch(RelevanceSearch relevance, PageTextIndex texts) {
        this.relevance = Objects.requireNonNull(relevance, "relevance");
        this.texts = texts;
    }

    /** Eine Suchanfrage ohne Variante: Finden und Vektorsuche mit demselben Text. */
    @Override
    public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
        return run(caller, query, query.text(), limit).results();
    }

    /**
     * @throws SemanticSearchUnavailableException wenn kein Embedding-Dienst eingerichtet oder er nicht erreichbar ist
     */
    @Override
    public Outcome run(AccessContext caller, KeywordSearch.Query variant, String original, int limit) {
        return run(caller, variant, original, limit, null);
    }

    /**
     * Die Relevanzsuche der Suchanfrage ist ein Teillauf von {@code partOf} im Suchlauf-Protokoll (#182), ihre
     * Schlagwortsuche zum Finden wiederum ein Teillauf von ihr.
     *
     * @throws SemanticSearchUnavailableException wenn kein Embedding-Dienst eingerichtet oder er nicht erreichbar ist
     */
    @Override
    public Outcome run(AccessContext caller, KeywordSearch.Query variant, String original, int limit,
                       SearchRunId partOf) {
        PageSearchQuery scope = scope(variant);
        // Ausgeschlossene Spaces laden nicht und sind keine Kandidaten: Erlaubte Treffer füllen das Limit.
        RelevanceSearch.RelevanceResults found = relevance.search(caller, scope, original, variant.text(),
                variant.excludedSpaceKeys(), Math.min(limit, Reranking.MAX_WINDOW), partOf);
        List<KeywordSearch.Hit> hits = new ArrayList<>(found.hits().size());
        Map<PageKey, Double> scores = new HashMap<>();
        for (RelevanceSearch.RankedHit ranked : found.hits()) {
            PageHeader header = ranked.hit().page().header();
            PageMetadata metadata = header.metadata();
            if (!variant.spaces().permits(metadata.partitionKey())) {
                continue;
            }
            PageKey key = header.key();
            hits.add(new KeywordSearch.Hit(key.sourceId(), key.pageId(), metadata.partitionKey(), metadata.title(),
                    snippet(key, ranked.relevance()), metadata.url(), metadata.version(), metadata.modifiedAt(),
                    metadata.parentPageId()));
            if (ranked.relevance() != null) {
                scores.put(key, ranked.relevance().similarity());
            }
        }
        return new Outcome(new KeywordSearch.Results(variant.sourceId(), hits, List.of(), null, found.complete(), 0),
                scores);
    }

    /**
     * Fail-closed wie die Relevanz-API: zuerst die Rechte am Umfang, dann der Embedding-Dienst.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Recht am genannten Umfang
     * @throws SemanticSearchUnavailableException ohne Embedding-Dienst; kein stiller Rückfall (ADR 0011)
     */
    @Override
    public void requireAvailable(AccessContext caller, KeywordSearch.Query query) {
        relevance.requireAvailable(caller, scope(query));
    }

    private static PageSearchQuery scope(KeywordSearch.Query query) {
        return new PageSearchQuery(query.sourceId(), query.sourceIds(), query.spaceKeys(), List.of(), null, null,
                null, null, null, SearchSort.TITLE);
    }

    /**
     * {@code profile} semantisch: {@link SearchProfile.Ranking#SEMANTIC} mit {@link #VARIANT_TIMEOUT} je Suchanfrage
     * und {@link #TIME_BUDGET}; Wellen, Parallelität, Wiederholung und Abfragebudget bleiben.
     */
    public static SearchProfile profile(SearchProfile profile) {
        SearchExecutionPolicy execution = profile.execution();
        return profile.withRanking(SearchProfile.Ranking.SEMANTIC)
                .withExecution(new SearchExecutionPolicy(execution.waveSize(), execution.parallelism(),
                        VARIANT_TIMEOUT, execution.retries()))
                .withBudget(TIME_BUDGET, profile.searchBudget());
    }

    /** Der Lesetext um das ähnlichste Stück, ohne Ähnlichkeit vom Anfang; leer ohne Volltextindex. */
    private String snippet(PageKey key, RelevanceSearch.Relevance relevance) {
        if (texts == null) {
            return "";
        }
        String text;
        try {
            text = texts.text(key).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
        int start = relevance == null ? 0 : Math.clamp(relevance.charStart(), 0, text.length());
        int end = Math.min(text.length(), start + MAX_SNIPPET_CHARS);
        if (relevance != null && relevance.charEnd() > start) {
            end = Math.min(end, relevance.charEnd());
        }
        return text.substring(start, end).replaceAll("\\s+", " ").strip();
    }
}

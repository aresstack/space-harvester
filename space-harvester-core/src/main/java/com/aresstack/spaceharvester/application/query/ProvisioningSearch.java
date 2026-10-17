package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Die Suche als Beschaffung (ADR 0010): Suchtext trifft Titel und Inhalt, der Bestand ist nie Voraussetzung. REST und
 * Desktop-App nutzen genau diesen Anwendungsfall.
 *
 * <p>Mit Suchtext fragt sie jede Quelle des Umfangs, die selbst suchen kann ({@link SourceCapability#LIVE_SEARCH}),
 * nach höchstens {@value #LIVE_LIMIT} Seiten (Confluence: CQL {@code siteSearch}, wie die Websuche). Jeder Treffer
 * geht über den Seitenabruf ({@link PageQueries#page}): fehlt die Seite im Bestand, wird sie geholt, abgelegt und
 * indexiert; die Rechte je Seite prüft dabei derselbe Weg wie beim Lesen, bei delegierten Benutzern über die Quelle.
 * Ausgeliefert wird jeder Treffer aus dem Suchindex mit allen Filtern der Anfrage und der Freigabe
 * {@link AccessKind#METADATA} an seinen aktuellen Kopfdaten. Nicht lesbare Treffer fallen ohne Spur weg. Dazu
 * kommen die Titeltreffer aus dem Index über alle Quellen des Umfangs, ohne Doppelte: so fehlen weder Quellen ohne
 * eigene Suche noch Seiten, die der Suchindex der Quelle noch nicht kennt.
 *
 * <p>Ohne Suchtext, mit Cursor, mit {@code pageId}, für Quellen ohne eigene Suche, ohne {@link AccessKind#CONTENT} für
 * die Quelle oder wenn die Quelle nicht antwortet, gilt die Titelsuche über den Index ({@link PageSearch}). Ist die
 * einzige Quelle nicht erreichbar und liegt nichts von ihr im Index, scheitert die Suche mit dem Fehler der Quelle.
 *
 * <p>Alle Treffer stehen in der Sortierung der Anfrage ({@link SearchSort#order()}). Als Unterklasse von
 * {@link PageSearch} ersetzt sie die Suche dort, wo bisher {@code PageSearch} stand ({@code Harvester.search()}).
 *
 * <p>Ein Stapel mit Treffern aus der Quelle hat keinen Cursor; {@code complete} heißt, dass keine Quelle mehr
 * Treffer hatte, als abgefragt wurden.
 */
public class ProvisioningSearch extends PageSearch {

    /** Höchstens so viele Treffer je Quelle und Anfrage. */
    public static final int LIVE_LIMIT = 25;

    /** Gleichzeitige Seitenabrufe einer Suche. */
    static final int PARALLEL_FETCHES = 6;

    private static final System.Logger LOG = System.getLogger(ProvisioningSearch.class.getName());

    private final SourceCatalog catalog;
    private final PageSearchIndex index;
    private final AccessGuard access;
    private final PageSearch indexSearch;
    private final PageQueries pages;

    public ProvisioningSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access, PageQueries pages) {
        super(catalog, index, access);
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.indexSearch = new PageSearch(catalog, index, access);
    }

    /**
     * Ein Stapel Treffer; Parameter und Fehler wie {@link PageSearch#search}, dazu:
     *
     * @throws SourceAccessException mit {@code sourceId}, wenn die Quelle nicht antwortet und nichts von ihr im Index
     *                               liegt
     */
    @Override
    public SearchResults search(AccessContext caller, PageSearchQuery query, String cursor, int limit) {
        if ((cursor != null && !cursor.isEmpty()) || query.titleTerms().isEmpty() || query.pageId() != null) {
            return indexSearch.search(caller, query, cursor, limit);
        }
        SearchScope scope = SearchScope.authorize(access, catalog, caller, query);
        if (limit < 1 || limit > PageSearch.MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + PageSearch.MAX_LIMIT + " liegen");
        }
        scope.requireKnown(catalog);
        String text = String.join(" ", query.titleTerms());

        Map<String, List<String>> found = new LinkedHashMap<>();
        SourceAccessException unreachable = null;
        for (HarvestSource source : catalog.all()) {
            String sourceId = source.id();
            if (!scope.sourceIds().contains(sourceId)) {
                continue;
            }
            if (!source.capabilities().contains(SourceCapability.LIVE_SEARCH)
                    || !access.permits(caller, ResourceScope.source(sourceId), AccessKind.CONTENT)) {
                continue;
            }
            try {
                found.put(sourceId, source.searchPages(text, LIVE_LIMIT));
            } catch (SourceAccessException e) {
                LOG.log(System.Logger.Level.INFO, "Suche in Quelle {0} nicht möglich ({1}), Suche im Index",
                        sourceId, e.reason());
                unreachable = e;
            }
        }
        if (found.isEmpty()) {
            try {
                return indexSearch.search(caller, query, null, limit);
            } catch (CacheMissException e) {
                if (unreachable != null) {
                    throw unreachable;
                }
                throw e;
            }
        }

        provision(caller, found);
        List<SearchHit> hits = new ArrayList<>();
        Set<PageKey> seen = new HashSet<>();
        boolean complete = true;
        for (Map.Entry<String, List<String>> entry : found.entrySet()) {
            complete &= entry.getValue().size() < LIVE_LIMIT;
            String sourceId = entry.getKey();
            SearchIndexState state = index.state(sourceId).orElse(null);
            if (state == null) {
                continue;
            }
            for (String pageId : entry.getValue()) {
                PageSearchQuery one = new PageSearchQuery(sourceId, Set.of(), query.spaceKeys(), List.of(), pageId,
                        query.parentPageId(), query.version(), query.modifiedFrom(), query.modifiedBefore(),
                        query.sort());
                for (SearchHit hit : index.search(one, Set.of(sourceId), state.generation(), null, 1)) {
                    if (visible(caller, hit) && seen.add(hit.page().header().key())) {
                        hits.add(hit);
                    }
                }
            }
        }
        // Dazu die Titeltreffer aus dem Index über alle Quellen des Umfangs: Quellen ohne eigene Suche oder gerade
        // nicht erreichbar, und Seiten, die der Suchindex der Quelle (noch) nicht kennt.
        PageSearchQuery rest = new PageSearchQuery(null, scope.sourceIds(), query.spaceKeys(), query.titleTerms(),
                null, query.parentPageId(), query.version(), query.modifiedFrom(), query.modifiedBefore(),
                query.sort());
        SearchResults more = indexSearch.search(caller, rest, null, PageSearch.MAX_LIMIT);
        complete &= more.complete();
        for (SearchHit hit : more.hits()) {
            if (seen.add(hit.page().header().key())) {
                hits.add(hit);
            }
        }
        // Die Sortierung der Anfrage gilt für alle Treffer, wie bei der Indexsuche.
        hits.sort(Comparator.comparing(SearchHit::page, query.sort().order()));
        if (hits.size() > limit) {
            hits = new ArrayList<>(hits.subList(0, limit));
            complete = false;
        }
        SearchIndexState state = scope.sourceIds().isEmpty() ? null : index.state(scope.sourceIds()).orElse(null);
        return new SearchResults(query.sourceId(), state, hits, null, complete, true);
    }

    /**
     * Holt fehlende Treffer über den Seitenabruf in den Bestand. Scheitert ein Abruf (nicht lesbar, nicht mehr da,
     * Quelle zu langsam), fehlt nur dieser Treffer.
     */
    private void provision(AccessContext caller, Map<String, List<String>> found) {
        Semaphore slots = new Semaphore(PARALLEL_FETCHES);
        try (ExecutorService fetches = Executors.newVirtualThreadPerTaskExecutor()) {
            found.forEach((sourceId, pageIds) -> pageIds.forEach(pageId -> fetches.submit(() -> {
                slots.acquireUninterruptibly();
                try {
                    pages.page(caller, sourceId, pageId, false);
                } catch (CacheMissException | AccessDeniedException | UnknownSourceException e) {
                    // Nicht vorhanden oder nicht lesbar: kein Treffer, keine Spur.
                } catch (RuntimeException e) {
                    // Ohne Seiten-ID im Log: der Treffer kann für den Aufrufer unsichtbar sein.
                    LOG.log(System.Logger.Level.INFO, "Suchtreffer in {0} nicht nachgeladen ({1})", sourceId,
                            e.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            })));
        }
    }

    private boolean visible(AccessContext caller, SearchHit hit) {
        var header = hit.page().header();
        return access.permits(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.METADATA);
    }
}

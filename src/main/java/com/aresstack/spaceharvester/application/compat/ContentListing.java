package com.aresstack.spaceharvester.application.compat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Seitenlisten mit Offset ({@code start}, {@code limit}) und nativer Payload je Seite, wie sie die Confluence-Fassade
 * (#31) für {@code /rest/api/content?spaceKey=…} und {@code /rest/api/content/{id}/child/page} braucht. Liest über die
 * Suche (#28) und die Einzelseite (#27), also mit deren Rechteprüfung; sichtbar ist eine Seite nur mit
 * {@code content}-Freigabe, wie bei Confluence fehlen nicht sichtbare Seiten ohne Hinweis.
 *
 * <p>Eine Liste wird nie stillschweigend unvollständig: Liegt eine Seite nur mit Kopfdaten im Cache oder ist der
 * Abgleich des Space unvollständig ({@link CacheState#INCOMPLETE}), scheitert die Abfrage mit
 * {@link IncompleteListingException}.
 */
public class ContentListing {

    /** Höchster Offset; begrenzt die Arbeit einer Anfrage. */
    static final int MAX_START = 10_000;

    /** Höchstes {@code limit}; größere Werte werden wie bei Confluence auf diesen Wert gesenkt. */
    public static final int MAX_LIMIT = PageSearch.MAX_LIMIT;

    private final PageSearch search;
    private final PageQueries pages;
    private final AccessGuard access;

    public ContentListing(PageSearch search, PageQueries pages, AccessGuard access) {
        this.search = Objects.requireNonNull(search, "search");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * Prüft, ob der Aufrufer überhaupt Metadaten dieser Quelle sehen darf, bevor die Fassade Parameter bewertet: Ein
     * Client ohne Freigabe erfährt nichts über die Route.
     *
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Freigabe für irgendeinen
     *                                                                                Space der Quelle
     */
    public void requireSource(AccessContext caller, String sourceId) {
        access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
    }

    /**
     * Seiten eines Space, aufsteigend nach {@code pageId}.
     *
     * @param title nur Seiten mit genau diesem Titel oder {@code null}
     */
    public Slice space(AccessContext caller, String sourceId, String spaceKey, String title, int start, int limit) {
        PageSearchQuery query = PageSearchQuery.of(sourceId, List.of(spaceKey), title, null, null, null, null, null,
                SearchSort.PAGE_ID);
        // Auch eine leere Liste darf nicht aus einem unvollständigen Abgleich stammen.
        requireComplete(pages.pages(caller, sourceId, spaceKey, null, 1).generation());
        return slice(caller, query, title, start, limit);
    }

    /** Direkte Kinder einer Seite in ihrem aktuellen Space, aufsteigend nach {@code pageId}. */
    public Slice children(AccessContext caller, String sourceId, String pageId, int start, int limit) {
        CachedPage parent = pages.page(caller, sourceId, pageId, false);
        requireComplete(parent.generation());
        PageSearchQuery query = PageSearchQuery.of(sourceId, List.of(parent.header().metadata().partitionKey()), null,
                null, pageId, null, null, null, SearchSort.PAGE_ID);
        return slice(caller, query, null, start, limit);
    }

    private Slice slice(AccessContext caller, PageSearchQuery query, String title, int start, int limit) {
        if (start < 0) {
            throw new InvalidQueryException("start darf nicht negativ sein");
        }
        if (start > MAX_START) {
            // Jede übersprungene Seite wird gelesen und auf Rechte geprüft; tiefer blättert man über die Seitenliste.
            throw new InvalidQueryException("start darf höchstens " + MAX_START + " sein");
        }
        if (limit < 0) {
            throw new InvalidQueryException("limit darf nicht negativ sein");
        }
        int effective = Math.min(limit, MAX_LIMIT);
        try {
            return read(caller, query, title, start, effective);
        } catch (CacheGenerationChangedException e) {
            // Ein Commit während des Blätterns: einmal neu lesen, dann aufgeben statt Stapel zu mischen.
            try {
                return read(caller, query, title, start, effective);
            } catch (CacheGenerationChangedException again) {
                throw new CacheUnavailableException("Der Cache ändert sich während des Lesens");
            }
        }
    }

    private Slice read(AccessContext caller, PageSearchQuery query, String title, int start, int limit) {
        List<SearchHit> visible = new ArrayList<>();
        int skipped = 0;
        boolean more = false;
        String cursor = null;
        SearchResults batch;
        do {
            batch = search.search(caller, query, cursor, PageSearch.MAX_LIMIT);
            for (SearchHit hit : batch.hits()) {
                if (!matches(hit, title) || !readable(caller, hit)) {
                    continue;
                }
                requireComplete(hit);
                if (skipped < start) {
                    skipped++;
                } else if (visible.size() < limit) {
                    visible.add(hit);
                } else {
                    more = true;
                    break;
                }
            }
            cursor = batch.nextCursor();
        } while (!more && !batch.complete());
        List<CachedPage> loaded = new ArrayList<>(visible.size());
        for (SearchHit hit : visible) {
            try {
                loaded.add(pages.page(caller, query.sourceId(), hit.page().pageId(), false));
            } catch (CacheMissException e) {
                // Zwischen Suche und Abruf entfernt oder unsichtbar geworden: wie ein neuer Stand behandeln.
                throw new CacheGenerationChangedException("Seite zwischen Suche und Abruf geändert");
            }
        }
        return new Slice(start, limit, loaded, more);
    }

    private static boolean matches(SearchHit hit, String title) {
        return title == null || title.equals(hit.page().header().metadata().title());
    }

    private boolean readable(AccessContext caller, SearchHit hit) {
        var header = hit.page().header();
        return access.canRead(caller, header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId());
    }

    private static void requireComplete(CacheGeneration generation) {
        if (generation.state() == CacheState.INCOMPLETE) {
            throw new IncompleteListingException("Der Abgleich des Space ist unvollständig");
        }
    }

    private static void requireComplete(SearchHit hit) {
        requireComplete(hit.generation());
        if (hit.page().rawSha256() == null) {
            throw new IncompleteListingException("Eine Seite liegt nur mit Kopfdaten im Cache");
        }
    }

    /**
     * Ein Ausschnitt der Liste.
     *
     * @param start Anzahl der übersprungenen sichtbaren Seiten
     * @param limit tatsächlich verwendetes Limit, höchstens {@link #MAX_LIMIT}
     * @param pages höchstens {@code limit} Seiten mit Payload
     * @param more  {@code true}, wenn hinter dem Ausschnitt weitere Seiten folgen
     */
    public record Slice(int start, int limit, List<CachedPage> pages, boolean more) {

        public Slice {
            pages = List.copyOf(pages);
        }
    }

    /** Die Liste ließe sich nur unvollständig liefern. */
    public static final class IncompleteListingException extends RuntimeException {

        public IncompleteListingException(String message) {
            super(message);
        }
    }
}

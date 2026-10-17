package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Titel- und Metadatensuche über den Index (#28, erste Ausbaustufe). Liest nur gespeicherte Kopfdaten, nie die
 * Quelle; Treffer liefern dieselben Felder wie die Seitenliste, aber keine Payload.
 *
 * <p>Rechte (#14): Mit {@code spaceKey} verlangt die Suche {@link AccessKind#METADATA} für jeden genannten Space, sonst
 * für die Quelle; danach prüft der {@link AccessGuard} jeden Treffer an seinen aktuellen Kopfdaten. Unsichtbare Treffer
 * fallen weg, ohne Spur in Anzahl oder Cursor.
 */
public class PageSearch {

    /** Größte Stapelgröße je Anfrage. */
    public static final int MAX_LIMIT = 100;

    private final SourceCatalog catalog;
    private final PageSearchIndex index;
    private final AccessGuard access;

    public PageSearch(SourceCatalog catalog, PageSearchIndex index, AccessGuard access) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.index = Objects.requireNonNull(index, "index");
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * Ein Stapel Treffer.
     *
     * @param cursor {@code null} oder leer für den ersten Stapel; sonst aus einer Antwort auf genau diese Anfrage
     * @param limit  1 bis {@value #MAX_LIMIT}
     * @throws AccessDeniedException           wenn ein genannter Space oder die Quelle nicht für {@code metadata}
     *                                         freigegeben ist
     * @throws InvalidQueryException           bei ungültigem Limit oder Cursor
     * @throws UnknownSourceException          wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws CacheMissException              wenn für die Quelle nichts im Index liegt
     * @throws CacheGenerationChangedException wenn der Cursor zu einer nicht mehr lesbaren Indexgeneration gehört
     */
    public SearchResults search(AccessContext caller, PageSearchQuery query, String cursor, int limit) {
        String sourceId = query.sourceId();
        if (query.spaceKeys().isEmpty()) {
            access.require(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
        } else {
            for (String spaceKey : query.spaceKeys()) {
                access.require(caller, ResourceScope.space(sourceId, spaceKey), AccessKind.METADATA);
            }
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        if (catalog.find(sourceId).isEmpty()) {
            throw new UnknownSourceException(sourceId);
        }
        SearchCursor position = cursor == null || cursor.isEmpty() ? null : SearchCursor.decode(cursor, query);
        SearchIndexState state = index.state(sourceId)
                .orElseThrow(() -> new CacheMissException(sourceId, "Quelle nicht im Suchindex"));
        if (position != null && !position.generation().equals(state.generation())) {
            throw new CacheGenerationChangedException("Der Cursor gehört zu einer früheren Indexgeneration");
        }

        // Wie die Seitenliste: lesen, bis das Limit mit sichtbaren Treffern gefüllt oder das Ende erreicht ist; der
        // Cursor zeigt hinter den letzten ausgelieferten Treffer.
        List<SearchHit> visible = new ArrayList<>();
        String after = position == null ? null : position.afterPosition();
        boolean exhausted = false;
        while (visible.size() < limit && !exhausted) {
            List<SearchHit> batch = index.search(query, state.generation(), after, limit);
            int consumed = 0;
            for (SearchHit hit : batch) {
                consumed++;
                after = hit.position();
                if (visible(caller, hit)) {
                    visible.add(hit);
                    if (visible.size() == limit) {
                        break;
                    }
                }
            }
            exhausted = batch.size() < limit && consumed == batch.size();
        }
        String next = exhausted ? null
                : SearchCursor.after(query, state.generation(), visible.get(visible.size() - 1).position()).encode();
        return new SearchResults(sourceId, state, visible, next, exhausted);
    }

    private boolean visible(AccessContext caller, SearchHit hit) {
        var header = hit.page().header();
        return access.permits(caller, ResourceScope.page(header.key().sourceId(), header.metadata().partitionKey(),
                header.key().pageId()), AccessKind.METADATA);
    }
}

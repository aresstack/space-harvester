package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchScope;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Abfragbare Fakten je Seite mit den Rechten des Aufrufers (#182). Fakten sind aus dem Inhalt abgeleitet: Jede Seite
 * braucht {@link AccessKind#METADATA} und {@link AccessKind#CONTENT}, geprüft an ihrem Space im Cache. Was der
 * Aufrufer nicht lesen darf, fällt ohne Spur weg; es gibt keine Gesamtzahl.
 *
 * <ul>
 *   <li>{@link #factsOf}: das Faktenblatt einer Seite (wie {@code factsOf(resource)} im Enterprise AI Client), über
 *       denselben Abruf wie das Lesen der Seite, mit der Aktualität jedes Fakts gegenüber der Payload.</li>
 *   <li>{@link #search}: Fakten eines Typs oder mit bestimmten Argumenten über alle lesbaren Quellen (wie
 *       {@code factsOfType} und {@code factsWithArguments}), seitenweise mit Cursor; liest nur den Cache, fragt keine
 *       Quelle außer bei delegierter Rechteprüfung.</li>
 * </ul>
 */
public final class PageFacts {

    /** Höchstens so viele Fakten je Stapel der Suche. */
    public static final int MAX_LIMIT = 200;
    /** So viele Fakten liest ein Stapel höchstens aus der Ablage, bevor er mit Cursor endet. */
    static final int MAX_SCANNED = 5_000;

    private static final Pattern CURSOR = Pattern.compile("[0-9a-f]{32}");

    private final SourceCatalog catalog;
    private final AccessGuard access;
    private final PageQueries pages;
    private final Enrichments enrichments;
    private final FactStore store;
    private final Function<PageKey, Optional<PageHeader>> headers;

    /**
     * @param enrichments für die Aktualität im Faktenblatt oder {@code null}, dann gilt jeder Fakt als veraltet
     * @param headers     Kopfdaten einer Seite im Cache (Space für die Rechteprüfung)
     */
    public PageFacts(SourceCatalog catalog, AccessGuard access, PageQueries pages, Enrichments enrichments,
                     FactStore store, Function<PageKey, Optional<PageHeader>> headers) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.enrichments = enrichments;
        this.store = Objects.requireNonNull(store, "store");
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    /**
     * Das Faktenblatt einer Seite. Rechte, Fehler und Aktualisierung wie {@link PageQueries#page}.
     *
     * @param type nur dieser Typ oder {@code null}
     */
    public Sheet factsOf(AccessContext caller, String sourceId, String pageId, String type) {
        if (type != null) {
            MetadataFact.requireName("type", type);
        }
        DeliveredPage page = pages.page(caller, sourceId, pageId, false);
        Map<String, Enrichment.Freshness> freshness = new HashMap<>();
        if (enrichments != null) {
            for (Enrichments.View view : enrichments.view(page.snapshot())) {
                freshness.put(view.enrichment().processorId() + '\u0000' + view.enrichment().inputSha256(),
                        view.freshness());
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (MetadataFact fact : store.factsOf(page.header().key())) {
            if (type == null || type.equals(fact.type())) {
                entries.add(new Entry(fact, freshness.getOrDefault(fact.provenance().processorId() + '\u0000'
                        + fact.provenance().inputSha256(), Enrichment.Freshness.STALE)));
            }
        }
        return new Sheet(sourceId, pageId, page.header().metadata().partitionKey(), entries);
    }

    /**
     * Ein Stapel Fakten über alle lesbaren Seiten im Umfang, aufsteigend nach Kennung.
     *
     * @param cursor {@link Results#nextCursor()} des vorigen Stapels oder {@code null}
     * @param limit  1 bis {@value #MAX_LIMIT}
     * @throws InvalidQueryException bei ungültigem Limit, Cursor, Typ oder ohne Typ und Bedingung
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException nur mit {@code sourceId}, wie bei
     *                                                                              {@link SearchScope#authorize}
     */
    public Results search(AccessContext caller, Search search, String cursor, int limit) {
        Objects.requireNonNull(search, "search");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidQueryException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        if (cursor != null && !cursor.isEmpty() && !CURSOR.matcher(cursor).matches()) {
            throw new InvalidQueryException("Ungültiger Cursor");
        }
        SearchScope scope = SearchScope.authorize(access, catalog, caller, search.scope());
        scope.requireKnown(catalog);
        if (scope.sourceIds().isEmpty()) {
            return new Results(List.of(), null);
        }
        FactStore.Query query = new FactStore.Query(search.type(), search.conditions(), scope.sourceIds());
        Map<PageKey, Boolean> readable = new HashMap<>();
        List<MetadataFact> found = new ArrayList<>();
        String after = cursor == null || cursor.isEmpty() ? null : cursor;
        int scanned = 0;
        boolean exhausted = false;
        while (found.size() < limit && scanned < MAX_SCANNED && !exhausted) {
            int batch = Math.min(FactStore.MAX_LIMIT, MAX_SCANNED - scanned);
            List<MetadataFact> next = store.facts(query, after, batch);
            int used = 0;
            for (MetadataFact fact : next) {
                if (found.size() == limit) {
                    break;
                }
                used++;
                scanned++;
                after = fact.id();
                if (readable.computeIfAbsent(fact.page(), key -> readable(caller, key, search.spaceKeys()))) {
                    found.add(fact);
                }
            }
            // Ablage erschöpft, wenn der Stapel kürzer war und ganz gelesen ist.
            exhausted = next.size() < batch && used == next.size();
        }
        return new Results(found, exhausted ? null : after);
    }

    /** Lesbar an den Kopfdaten im Cache, im gewünschten Space; ohne Kopfdaten nicht. */
    private boolean readable(AccessContext caller, PageKey key, Set<String> spaceKeys) {
        Optional<PageHeader> header;
        try {
            header = headers.apply(key);
        } catch (RuntimeException e) {
            return false;
        }
        if (header.isEmpty()) {
            return false;
        }
        String spaceKey = header.get().metadata().partitionKey();
        if (!spaceKeys.isEmpty() && !spaceKeys.contains(spaceKey)) {
            return false;
        }
        ResourceScope scope = ResourceScope.page(key.sourceId(), spaceKey, key.pageId());
        return access.permits(caller, scope, AccessKind.METADATA) && access.permits(caller, scope, AccessKind.CONTENT);
    }

    /**
     * Eine Suche nach Fakten.
     *
     * @param type       nur dieser Typ oder {@code null}
     * @param conditions Argumentbedingungen, alle zugleich, höchstens {@value FactStore.Query#MAX_CONDITIONS}
     * @param sourceId   nur diese Quelle oder {@code null}
     * @param sourceIds  ohne {@code sourceId}: nur diese Quellen, leer für alle lesbaren
     * @param spaceKeys  nur Seiten dieser Spaces, leer für alle
     */
    public record Search(String type, List<ArgumentCondition> conditions, String sourceId, Set<String> sourceIds,
                         Set<String> spaceKeys) {

        public Search {
            conditions = List.copyOf(conditions);
            if (conditions.size() > FactStore.Query.MAX_CONDITIONS) {
                throw new InvalidQueryException("argument höchstens " + FactStore.Query.MAX_CONDITIONS + "-mal");
            }
            try {
                if (type != null) {
                    MetadataFact.requireName("type", type);
                }
            } catch (IllegalArgumentException e) {
                throw new InvalidQueryException(e.getMessage());
            }
            if (type == null && conditions.isEmpty()) {
                throw new InvalidQueryException("type oder argument fehlt");
            }
            PageSearchQuery checked = PageSearchQuery.of(sourceId, List.copyOf(sourceIds == null ? Set.of()
                    : sourceIds), List.copyOf(spaceKeys == null ? Set.of() : spaceKeys), null, null, null, null,
                    null, null, SearchSort.TITLE);
            sourceId = checked.sourceId();
            sourceIds = checked.sourceIds();
            spaceKeys = checked.spaceKeys();
        }

        /** Fakten eines Typs in allen lesbaren Quellen. */
        public static Search ofType(String type) {
            return new Search(type, List.of(), null, Set.of(), Set.of());
        }

        PageSearchQuery scope() {
            return new PageSearchQuery(sourceId, sourceIds, spaceKeys, List.of(), null, null, null, null, null,
                    SearchSort.TITLE);
        }
    }

    /**
     * Ein Stapel.
     *
     * @param facts      lesbare Fakten, aufsteigend nach Kennung; kann kürzer als das Limit sein, auch wenn noch welche
     *                   folgen
     * @param nextCursor für den nächsten Stapel oder {@code null}, wenn die Ablage erschöpft ist
     */
    public record Results(List<MetadataFact> facts, String nextCursor) {

        public Results {
            facts = List.copyOf(facts);
        }
    }

    /** Das Faktenblatt einer Seite. */
    public record Sheet(String sourceId, String pageId, String spaceKey, List<Entry> facts) {

        public Sheet {
            facts = List.copyOf(facts);
        }
    }

    /** Ein Fakt mit seiner Aktualität gegenüber der ausgelieferten Payload. */
    public record Entry(MetadataFact fact, Enrichment.Freshness freshness) {
    }
}

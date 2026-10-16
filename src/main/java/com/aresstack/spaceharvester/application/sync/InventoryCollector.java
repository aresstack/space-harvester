package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Liest alle Seitenmetadaten eines Space über sämtliche Cursor und prüft dabei, ob der Bestand in sich stimmig ist.
 *
 * <p>Ein Fehler der Quelle bricht die Inventur ab ({@link Inventory.Status#INCOMPLETE}). Widersprüche machen sie
 * {@link Inventory.Status#INCONSISTENT}: eine Seite aus einem anderen Space, dieselbe Seite mit verschiedenen
 * Fingerprints in zwei Stapeln (während der Listung geändert) oder ein Cursor, der sich wiederholt. Dieselbe Seite
 * mehrfach mit gleichen Fingerprints ist unschädlich und zählt einmal. Unbekannte Quelle oder ungültiges Limit sind
 * Konfigurationsfehler und werden weitergeworfen.
 */
public class InventoryCollector {

    private final SourceQueries queries;
    private final Clock clock;
    private final int batchSize;

    public InventoryCollector(SourceQueries queries, Clock clock) {
        this(queries, clock, SourceQueries.MAX_PAGE_METADATA_LIMIT);
    }

    public InventoryCollector(SourceQueries queries, Clock clock, int batchSize) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = batchSize;
    }

    public Inventory collect(String sourceId, String spaceKey) {
        Instant startedAt = clock.instant();
        Map<String, PageMetadataListing.Entry> pages = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            PageMetadataListing listing;
            try {
                listing = queries.pageMetadata(sourceId, spaceKey, cursor, batchSize);
            } catch (SourceAccessException e) {
                return result(sourceId, spaceKey, Inventory.Status.INCOMPLETE,
                        "Listung abgebrochen (" + e.reason() + "): " + e.getMessage(), pages, startedAt);
            }
            for (PageMetadataListing.Entry entry : listing.entries()) {
                String pageId = entry.page().pageId();
                if (!spaceKey.equals(entry.page().partitionKey())) {
                    return result(sourceId, spaceKey, Inventory.Status.INCONSISTENT, "Seite " + pageId
                            + " gehört laut Quelle zu Space " + entry.page().partitionKey(), pages, startedAt);
                }
                PageMetadataListing.Entry earlier = pages.putIfAbsent(pageId, entry);
                if (earlier != null && !(earlier.metadataHash().equals(entry.metadataHash())
                        && earlier.attributesHash().equals(entry.attributesHash()))) {
                    return result(sourceId, spaceKey, Inventory.Status.INCONSISTENT,
                            "Seite " + pageId + " hat sich während der Listung geändert", pages, startedAt);
                }
            }
            cursor = listing.nextCursor();
            if (cursor != null && !cursors.add(cursor)) {
                return result(sourceId, spaceKey, Inventory.Status.INCONSISTENT,
                        "Die Quelle hat denselben Cursor wiederholt geliefert", pages, startedAt);
            }
        } while (cursor != null);
        return result(sourceId, spaceKey, Inventory.Status.COMPLETE, null, pages, startedAt);
    }

    private Inventory result(String sourceId, String spaceKey, Inventory.Status status, String problem,
                             Map<String, PageMetadataListing.Entry> pages, Instant startedAt) {
        return new Inventory(sourceId, spaceKey, status, problem, List.copyOf(pages.values()), startedAt,
                clock.instant());
    }
}

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
        return collect(sourceId, spaceKey, () -> { });
    }

    /** Check cancellation before each new inventory page instead of blocking for an entire tree crawl. */
    public Inventory collect(String sourceId, String spaceKey, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Instant startedAt = clock.instant();
        Map<String, PageMetadataListing.Entry> pages = new LinkedHashMap<>();
        try {
            checkpoint.run();
            boolean visited = queries.visitInventory(sourceId, spaceKey, checkpoint,
                    entry -> add(spaceKey, pages, entry));
            if (!visited) {
                collectBatches(sourceId, spaceKey, checkpoint, pages);
            }
            return result(sourceId, spaceKey, Inventory.Status.COMPLETE, null, pages, startedAt);
        } catch (SourceAccessException failure) {
            return result(sourceId, spaceKey, Inventory.Status.INCOMPLETE,
                    "Listung abgebrochen (" + failure.reason() + "): " + failure.getMessage(), pages, startedAt);
        } catch (InconsistentInventory failure) {
            return result(sourceId, spaceKey, Inventory.Status.INCONSISTENT, failure.getMessage(), pages, startedAt);
        }
    }

    private void collectBatches(String sourceId, String spaceKey, Runnable checkpoint,
                                Map<String, PageMetadataListing.Entry> pages) {
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            checkpoint.run();
            PageMetadataListing listing = queries.pageMetadata(sourceId, spaceKey, cursor, batchSize);
            for (PageMetadataListing.Entry entry : listing.entries()) {
                add(spaceKey, pages, entry);
            }
            cursor = listing.nextCursor();
            if (cursor != null && !cursors.add(cursor)) {
                throw new InconsistentInventory("Die Quelle hat denselben Cursor wiederholt geliefert");
            }
        } while (cursor != null);
    }

    private static void add(String spaceKey, Map<String, PageMetadataListing.Entry> pages,
                            PageMetadataListing.Entry entry) {
        String pageId = entry.page().pageId();
        if (!spaceKey.equals(entry.page().partitionKey())) {
            throw new InconsistentInventory("Seite " + pageId + " gehört laut Quelle zu Space "
                    + entry.page().partitionKey());
        }
        PageMetadataListing.Entry earlier = pages.putIfAbsent(pageId, entry);
        if (earlier != null && !(earlier.metadataHash().equals(entry.metadataHash())
                && earlier.attributesHash().equals(entry.attributesHash()))) {
            throw new InconsistentInventory("Seite " + pageId + " hat sich während der Listung geändert");
        }
    }

    private static final class InconsistentInventory extends RuntimeException {
        private InconsistentInventory(String message) {
            super(message);
        }
    }

    private Inventory result(String sourceId, String spaceKey, Inventory.Status status, String problem,
                             Map<String, PageMetadataListing.Entry> pages, Instant startedAt) {
        return new Inventory(sourceId, spaceKey, status, problem, List.copyOf(pages.values()), startedAt,
                clock.instant());
    }
}

package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Nachholen verpasster Änderungen eines Space (ADR 0014): statt den Space neu zu inventarisieren, fragt der Lauf die
 * Quelle einmal nach den seit {@code since} geänderten Seiten und einmal nach ihrem Papierkorb
 * ({@link com.aresstack.spaceharvester.application.sources.HarvestSource#listsChanges()}).
 *
 * <ul>
 *   <li>Geändert und bekannt (Generation oder Cache): wie im Abgleich eingeordnet, also neuer Inhalt nur bei neuer
 *       Version, sonst Kopfdaten.</li>
 *   <li>Geändert, aber unbekannt: nur die Kopfdaten in den Katalog ({@link PageChange.Kind#NOT_LOADED}); Inhalte holt
 *       erst die Suche oder ein Ereignis. Die Betriebsart ({@link SyncMode}) kann das ändern.</li>
 *   <li>Im Papierkorb und bekannt: autoritativ gelöscht ({@link RemovalCheck.Verdict#DELETED}), aus Cache und Indizes
 *       entfernt.</li>
 * </ul>
 *
 * <p>Seiten, die den Space verlassen haben oder endgültig aus dem Papierkorb gelöscht wurden, erkennt erst eine
 * Inventur ({@link SpaceSync}). Wie dort wird alles erst mit der nächsten Generation sichtbar; scheitert eine Anfrage,
 * bleibt alles beim Alten und der nächste Lauf fragt ab demselben Zeitpunkt.
 */
public class ChangeSync {

    /** Überlappung des Zeitfensters, damit eine Änderung genau an der Grenze nicht durchrutscht. */
    public static final Duration OVERLAP = Duration.ofMinutes(2);

    private final SourceQueries queries;
    private final SyncBaselineStore store;
    private final Clock clock;
    private final int batchSize;

    public ChangeSync(SourceQueries queries, SyncBaselineStore store, Clock clock) {
        this(queries, store, clock, SourceQueries.MAX_PAGE_METADATA_LIMIT);
    }

    ChangeSync(SourceQueries queries, SyncBaselineStore store, Clock clock, int batchSize) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = batchSize;
    }

    /** Ob die Quelle Änderungen gezielt meldet; sonst bleibt nur die Inventur. */
    public boolean supported(String sourceId) {
        return queries.listsChanges(sourceId);
    }

    /**
     * Holt die Änderungen seit {@code since} nach.
     *
     * @param since ab wann, oder {@code null} für den Stand der bestätigten Generation
     *              ({@link SyncBaseline#changesSince()}). Ohne beides fragt der Lauf die Quelle nicht, sondern legt nur
     *              den Ausgangspunkt fest: Ab jetzt geht keine Änderung mehr verloren; was der Cache vorher kannte,
     *              prüft der Abruf über seine Frische
     * @throws SyncConflictException wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    public SpaceSync.SyncRun run(String sourceId, String spaceKey, Instant since, PlanApplier applier,
                                 SyncListener listener) {
        return run(sourceId, spaceKey, since, applier, listener, SyncMode.SYNC_CACHE);
    }

    /**
     * Wie {@link #run(String, String, Instant, PlanApplier, SyncListener)}, in der Betriebsart {@code mode}:
     * {@link SyncMode#FULL} lädt auch unbekannte geänderte Seiten, {@link SyncMode#CATALOG_ONLY} lädt gar keine
     * Inhalte.
     */
    public SpaceSync.SyncRun run(String sourceId, String spaceKey, Instant since, PlanApplier applier,
                                 SyncListener listener, SyncMode mode) {
        SyncBaseline baseline = store.confirmed(sourceId, spaceKey).orElse(null);
        Instant startedAt = clock.instant();
        Instant from = since != null ? since : baseline == null ? null : baseline.changesSince();
        List<PageMetadataListing.Entry> changed = List.of();
        List<PageMetadataListing.Entry> removed = List.of();
        if (from != null) {
            Duration within = Duration.between(from, startedAt).plus(OVERLAP);
            try {
                changed = collect(cursor -> queries.changedPageMetadata(sourceId, spaceKey, within, cursor,
                        batchSize));
                removed = collect(cursor -> queries.removedPageMetadata(sourceId, spaceKey, cursor, batchSize));
            } catch (SourceAccessException e) {
                Inventory failed = new Inventory(sourceId, spaceKey, Inventory.Status.INCOMPLETE,
                        "Änderungssuche abgebrochen (" + e.reason() + "): " + e.getMessage(), List.of(), startedAt,
                        clock.instant());
                listener.inventoried(failed);
                return new SpaceSync.SyncRun(new SyncPlan(failed, baseline, List.of(), Map.of()), Set.of(), null,
                        failed.status() + ": " + failed.problem());
            }
        }
        Map<String, PageMetadataListing.Entry> current = new LinkedHashMap<>();
        for (PageMetadataListing.Entry entry : changed) {
            if (spaceKey.equals(entry.page().partitionKey())) {
                current.put(entry.page().pageId(), entry);
            }
        }
        Set<String> trashed = new LinkedHashSet<>();
        removed.forEach(entry -> trashed.add(entry.page().pageId()));
        trashed.forEach(current::remove);
        Inventory inventory = new Inventory(sourceId, spaceKey, Inventory.Status.CHANGES, null,
                List.copyOf(current.values()), startedAt, clock.instant());
        listener.inventoried(inventory);

        Map<String, BaselineEntry> known = new HashMap<>(baseline == null ? Map.of() : baseline.pages());
        known.putAll(store.knownPages(sourceId, spaceKey));
        List<PageChange> changes = new ArrayList<>();
        current.forEach((pageId, entry) -> {
            BaselineEntry previous = known.get(pageId);
            changes.add(SyncPlanner.within(mode, SyncPlanner.classify(previous, entry)));
        });
        Map<String, RemovalCheck.Result> removals = new HashMap<>();
        for (String pageId : trashed) {
            if (known.containsKey(pageId) || baseline != null && (baseline.missingPageIds().contains(pageId)
                    || baseline.withheldPageIds().containsKey(pageId))) {
                changes.add(new PageChange(pageId, PageChange.Kind.MISSING, known.get(pageId), null));
                removals.put(pageId, new RemovalCheck.Result(pageId, RemovalCheck.Verdict.DELETED, null, null));
            }
        }
        changes.sort(Comparator.comparing(PageChange::pageId));
        SyncPlan plan = new SyncPlan(inventory, baseline, changes, removals);
        try (StagedGeneration staged = store.begin(sourceId, spaceKey)) {
            Set<String> failed = applier.apply(plan, staged, listener);
            SyncBaseline next = next(baseline, known, plan, failed, startedAt);
            listener.checkpoint();
            staged.commit(next);
            return new SpaceSync.SyncRun(plan, failed, next, null);
        }
    }

    /** Die bisherige Generation, fortgeschrieben um die Änderungen; ohne Generation das, was der Cache kennt. */
    private SyncBaseline next(SyncBaseline baseline, Map<String, BaselineEntry> known, SyncPlan plan,
                              Set<String> failed, Instant startedAt) {
        SortedMap<String, BaselineEntry> pages = new TreeMap<>(baseline == null ? known : baseline.pages());
        SortedSet<String> stale = new TreeSet<>(baseline == null ? Set.of() : baseline.stalePageIds());
        SortedSet<String> missing = new TreeSet<>(baseline == null ? Set.of() : baseline.missingPageIds());
        SortedMap<String, Instant> withheld = new TreeMap<>(baseline == null ? Map.of() : baseline.withheldPageIds());
        Set<String> unknownFailures = new HashSet<>(failed);
        for (PageChange change : plan.changes()) {
            String pageId = change.pageId();
            unknownFailures.remove(pageId);
            if (change.kind() == PageChange.Kind.MISSING) {
                pages.remove(pageId);
                stale.remove(pageId);
                missing.remove(pageId);
                withheld.remove(pageId);
                continue;
            }
            // Eine gelistete Seite ist für das technische Konto wieder sichtbar.
            withheld.remove(pageId);
            if (change.kind() == PageChange.Kind.NOT_LOADED) {
                if (!pages.containsKey(pageId)) {
                    missing.add(pageId);
                }
            } else if (change.kind() == PageChange.Kind.OUTDATED) {
                pages.put(pageId, change.previous());
                stale.add(pageId);
            } else if (failed.contains(pageId)) {
                if (change.previous() != null) {
                    pages.put(pageId, change.previous());
                    stale.add(pageId);
                    missing.remove(pageId);
                } else {
                    missing.add(pageId);
                }
            } else {
                pages.put(pageId, BaselineEntry.of(change.current()));
                stale.remove(pageId);
                missing.remove(pageId);
            }
        }
        if (!unknownFailures.isEmpty()) {
            throw new IllegalArgumentException("Seiten nicht im Plan: " + new TreeSet<>(unknownFailures));
        }
        missing.removeAll(pages.keySet());
        Instant confirmedAt = clock.instant();
        long generation = baseline == null ? 1 : baseline.generation() + 1;
        return new SyncBaseline(plan.inventory().sourceId(), plan.inventory().spaceKey(), generation, confirmedAt,
                pages, stale, missing, withheld, startedAt.isAfter(confirmedAt) ? confirmedAt : startedAt);
    }

    private static List<PageMetadataListing.Entry> collect(Function<String, PageMetadataListing> batch) {
        List<PageMetadataListing.Entry> entries = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            PageMetadataListing listing = batch.apply(cursor);
            entries.addAll(listing.entries());
            cursor = listing.nextCursor();
            if (cursor != null && !cursors.add(cursor)) {
                throw new SourceAccessException(listing.sourceId(), SourceAccessException.Reason.UNAVAILABLE,
                        "Die Quelle hat denselben Cursor wiederholt geliefert", null);
            }
        } while (cursor != null);
        return entries;
    }
}

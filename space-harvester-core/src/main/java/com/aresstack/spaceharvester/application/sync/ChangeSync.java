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
 * Nachholen verpasster Änderungen eines Space (ADR 0014): Der Lauf fragt die Quelle nach den seit {@code since}
 * geänderten Seiten ({@link com.aresstack.spaceharvester.application.sources.HarvestSource#listsChanges()}) und
 * vergleicht die vollständige Seitenliste des Space mit dem Bekannten, um gelöschte Seiten zu erkennen.
 *
 * <ul>
 *   <li>Geändert und bekannt (Generation oder Cache): wie im Abgleich eingeordnet, also neuer Inhalt nur bei neuer
 *       Version, sonst Kopfdaten.</li>
 *   <li>Geändert, aber unbekannt: nur die Kopfdaten in den Katalog ({@link PageChange.Kind#NOT_LOADED}); Inhalte holt
 *       erst die Suche oder ein Ereignis. Die Betriebsart ({@link SyncMode}) kann das ändern.</li>
 *   <li>Bekannt, aber weder geändert noch in der Seitenliste: einzeln nachgefragt ({@link RemovalCheck}) und wie in der
 *       Inventur entfernt oder gesperrt. Den Papierkorb fragt der Lauf nicht: Ihn dürfen oft nur Space-Administratoren
 *       lesen.</li>
 * </ul>
 *
 * <p>Wie in der Inventur wird alles erst mit der nächsten Generation sichtbar; scheitert die Änderungsliste, bleibt
 * alles beim Alten und der nächste Lauf fragt ab demselben Zeitpunkt. Scheitert nur die Seitenliste, gelten die
 * Änderungen trotzdem, Löschungen prüft der nächste Lauf.
 */
public class ChangeSync {

    private static final System.Logger LOG = System.getLogger(ChangeSync.class.getName());

    /** Überlappung des Zeitfensters, damit eine Änderung genau an der Grenze nicht durchrutscht. */
    public static final Duration OVERLAP = Duration.ofMinutes(2);

    private final SourceQueries queries;
    private final SyncBaselineStore store;
    private final Clock clock;
    private final int batchSize;
    private final InventoryCollector collector;
    private final RemovalCheck removalCheck;
    /** Frist gesperrter Seiten je Quelle, wie in der Inventur ({@link SpaceSync}). */
    private final Function<String, Duration> withheldRetention;
    /** Was indexiert wird; jeder Lauf liest die Markierungen neu. */
    private volatile IndexMarks marks = IndexMarks.NONE;

    public ChangeSync(SourceQueries queries, SyncBaselineStore store, Clock clock) {
        this(queries, store, clock, sourceId -> SpaceSync.DEFAULT_WITHHELD_RETENTION);
    }

    /** @param withheldRetention Frist gesperrter Seiten je Quellen-ID, danach entfernt der Lauf ihre Rohdaten */
    public ChangeSync(SourceQueries queries, SyncBaselineStore store, Clock clock,
                      Function<String, Duration> withheldRetention) {
        this(queries, store, clock, SourceQueries.MAX_PAGE_METADATA_LIMIT, withheldRetention);
    }

    ChangeSync(SourceQueries queries, SyncBaselineStore store, Clock clock, int batchSize) {
        this(queries, store, clock, batchSize, sourceId -> SpaceSync.DEFAULT_WITHHELD_RETENTION);
    }

    ChangeSync(SourceQueries queries, SyncBaselineStore store, Clock clock, int batchSize,
               Function<String, Duration> withheldRetention) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = batchSize;
        this.collector = new InventoryCollector(queries, clock, batchSize);
        this.removalCheck = new RemovalCheck(queries);
        this.withheldRetention = Objects.requireNonNull(withheldRetention, "withheldRetention");
    }

    /** Geänderte Seiten unter diesen Markierungen lädt jeder Lauf vollständig, auch unbekannte. */
    public void indexMarks(IndexMarks marks) {
        this.marks = Objects.requireNonNull(marks, "marks");
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
        if (from != null) {
            Duration within = Duration.between(from, startedAt).plus(OVERLAP);
            try {
                changed = collect(cursor -> queries.changedPageMetadata(sourceId, spaceKey, within, cursor,
                        batchSize));
            } catch (SourceAccessException e) {
                return aborted(sourceId, spaceKey, baseline, startedAt, listener, e);
            }
        }
        Map<String, PageMetadataListing.Entry> current = new LinkedHashMap<>();
        for (PageMetadataListing.Entry entry : changed) {
            if (spaceKey.equals(entry.page().partitionKey())) {
                current.put(entry.page().pageId(), entry);
            }
        }
        // Gelöschte Seiten: die vollständige Seitenliste des Space, verglichen mit dem Bekannten (wie die Inventur).
        Inventory listing = from == null ? null : collector.collect(sourceId, spaceKey, listener::checkpoint);
        Set<String> listedIds = new HashSet<>();
        if (listing != null && listing.confirmed()) {
            listing.pages().forEach(entry -> listedIds.add(entry.page().pageId()));
            if (baseline != null) {
                // Gesperrte Seiten, die die Seitenliste wieder nennt, sind wieder sichtbar: wie geändert behandeln.
                listing.byPageId().forEach((pageId, entry) -> {
                    if (baseline.withheldPageIds().containsKey(pageId)) {
                        current.putIfAbsent(pageId, entry);
                    }
                });
            }
        }
        Set<String> reload = from == null || !mode.refreshesContent() ? Set.of()
                : stale(sourceId, spaceKey, baseline, current, listener);
        Inventory inventory = new Inventory(sourceId, spaceKey, Inventory.Status.CHANGES, null,
                List.copyOf(current.values()), startedAt, clock.instant());
        listener.inventoried(inventory);

        Map<String, BaselineEntry> known = new HashMap<>(baseline == null ? Map.of() : baseline.pages());
        known.putAll(store.knownPages(sourceId, spaceKey));
        // Indexieren: Vorfahren aus den gemeldeten Seiten, sonst aus Generation und Cache.
        java.util.function.Predicate<String> indexed = IndexScope.of(marks.roots(sourceId, spaceKey), pageId -> {
            PageMetadataListing.Entry listed = current.get(pageId);
            if (listed != null) {
                return listed.page().parentPageId();
            }
            BaselineEntry stored = known.get(pageId);
            return stored == null ? null : stored.parentPageId();
        });
        List<PageChange> changes = new ArrayList<>();
        current.forEach((pageId, entry) -> {
            BaselineEntry previous = known.get(pageId);
            if (reload.contains(pageId) && previous != null) {
                // Veraltet aus einem früheren Lauf ohne Inhalte: immer laden, auch wenn die Version schon bekannt ist.
                changes.add(new PageChange(pageId, PageChange.Kind.CONTENT_CHANGED, previous, entry));
            } else {
                changes.add(SyncPlanner.within(indexed.test(pageId) ? SyncMode.FULL : mode,
                        SyncPlanner.classify(previous, entry)));
            }
        });
        Map<String, RemovalCheck.Result> removals = new HashMap<>();
        if (listing != null && listing.confirmed()) {
            removals(listing, baseline, known, current.keySet(), listedIds).forEach(removal -> {
                changes.add(new PageChange(removal.pageId(), PageChange.Kind.MISSING, known.get(removal.pageId()),
                        null));
                removals.put(removal.pageId(), removal);
            });
        } else if (listing != null) {
            // Ohne vollständige Liste keine Löschung: die Änderungen gelten, Löschungen erkennt der nächste Lauf.
            LOG.log(System.Logger.Level.INFO, "Änderungssuche {0}/{1}: Seitenliste {2}, Löschungen nicht geprüft",
                    sourceId, spaceKey, listing.status());
        }
        changes.sort(Comparator.comparing(PageChange::pageId));
        SyncPlan plan = new SyncPlan(inventory, baseline, changes, removals,
                purgesDue(sourceId, listing, baseline, listedIds));
        try (StagedGeneration staged = store.begin(sourceId, spaceKey)) {
            Set<String> failed = applier.apply(plan, staged, listener);
            SyncBaseline next = next(baseline, known, plan, failed, startedAt);
            listener.checkpoint();
            staged.commit(next);
            return new SpaceSync.SyncRun(plan, failed, next, null);
        }
    }

    /**
     * Bekannte Seiten (Generation, Cache, nicht geladene), die weder die Änderungsliste noch die vollständige
     * Seitenliste nennt, fragt der Lauf einzeln nach ({@link RemovalCheck}): gelöscht, nicht mehr sichtbar oder
     * verschoben fallen aus dem Space; was nicht prüfbar oder doch vorhanden ist, bleibt.
     */
    private List<RemovalCheck.Result> removals(Inventory listing, SyncBaseline baseline,
                                               Map<String, BaselineEntry> known, Set<String> changed,
                                               Set<String> listed) {
        Set<String> candidates = new TreeSet<>(known.keySet());
        if (baseline != null) {
            candidates.addAll(baseline.missingPageIds());
        }
        candidates.removeAll(listed);
        candidates.removeAll(changed);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<PageChange> missing = candidates.stream()
                .map(pageId -> new PageChange(pageId, PageChange.Kind.MISSING, known.get(pageId), null)).toList();
        return removalCheck.verify(new SyncPlan(listing, baseline, missing, Map.of())).values().stream()
                .filter(RemovalCheck.Result::removes).toList();
    }

    /**
     * Wie in der Inventur ({@link SyncPlan#withPurgesDue}): gesperrte Seiten, deren Frist abgelaufen ist und die die
     * vollständige Seitenliste nicht wieder nennt, entfernt der Lauf endgültig; ohne vollständige Liste keine.
     */
    private Set<String> purgesDue(String sourceId, Inventory listing, SyncBaseline baseline, Set<String> listed) {
        if (listing == null || !listing.confirmed() || baseline == null) {
            return Set.of();
        }
        Duration retention = withheldRetention.apply(sourceId);
        Instant cutoff = clock.instant().minus(retention == null || retention.isNegative()
                ? SpaceSync.DEFAULT_WITHHELD_RETENTION : retention);
        Set<String> due = new TreeSet<>();
        baseline.withheldPageIds().forEach((pageId, since) -> {
            if (!listed.contains(pageId) && !since.isAfter(cutoff)) {
                due.add(pageId);
            }
        });
        return due;
    }

    /** Die Änderungssuche ist gescheitert: nichts wird geschrieben, die Generation bleibt, der Grund reist mit. */
    private SpaceSync.SyncRun aborted(String sourceId, String spaceKey, SyncBaseline baseline, Instant startedAt,
                                      SyncListener listener, SourceAccessException e) {
        Inventory failed = new Inventory(sourceId, spaceKey, Inventory.Status.INCOMPLETE,
                "Änderungssuche abgebrochen (" + e.reason() + "): " + e.getMessage(), List.of(), startedAt,
                clock.instant());
        listener.inventoried(failed);
        return new SpaceSync.SyncRun(new SyncPlan(failed, baseline, List.of(), Map.of()), Set.of(), null,
                failed.status() + ": " + failed.problem(), e);
    }

    /**
     * Seiten, die die Generation als veraltet führt und die die Änderungssuche nicht nennt (etwa aus einem früheren Lauf
     * „nur katalogisieren“, dessen Änderungen vor {@code changesSince} liegen): Lädt der Lauf Inhalte, fragt er ihre
     * Kopfdaten einzeln nach und nimmt sie in {@code current} auf, damit „Cache synchronisieren“ sie wirklich auffrischt.
     * Was die Quelle nicht liefert, bleibt veraltet für den nächsten Lauf.
     *
     * @return die aufgenommenen Seiten
     */
    private Set<String> stale(String sourceId, String spaceKey, SyncBaseline baseline,
                              Map<String, PageMetadataListing.Entry> current, SyncListener listener) {
        if (baseline == null || baseline.stalePageIds().isEmpty()) {
            return Set.of();
        }
        Set<String> added = new HashSet<>();
        for (String pageId : baseline.stalePageIds()) {
            if (current.containsKey(pageId)) {
                continue;
            }
            listener.checkpoint();
            PageMetadataListing.Entry entry;
            try {
                entry = queries.pageMetadata(sourceId, pageId).entry();
            } catch (SourceAccessException e) {
                if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                    continue;
                }
                // Quelle gerade nicht erreichbar: der Rest bleibt veraltet für den nächsten Lauf.
                break;
            }
            if (spaceKey.equals(entry.page().partitionKey()) && pageId.equals(entry.page().pageId())) {
                current.put(pageId, entry);
                added.add(pageId);
            }
        }
        return added;
    }

    /** Die bisherige Generation, fortgeschrieben um die Änderungen; ohne Generation das, was der Cache kennt. */
    private SyncBaseline next(SyncBaseline baseline, Map<String, BaselineEntry> known, SyncPlan plan,
                              Set<String> failed, Instant startedAt) {
        SortedMap<String, BaselineEntry> pages = new TreeMap<>(baseline == null ? known : baseline.pages());
        SortedSet<String> stale = new TreeSet<>(baseline == null ? Set.of() : baseline.stalePageIds());
        SortedSet<String> missing = new TreeSet<>(baseline == null ? Set.of() : baseline.missingPageIds());
        SortedMap<String, Instant> withheld = new TreeMap<>(baseline == null ? Map.of() : baseline.withheldPageIds());
        Set<String> unknownFailures = new HashSet<>(failed);
        Instant confirmedAt = clock.instant();
        for (PageChange change : plan.changes()) {
            String pageId = change.pageId();
            unknownFailures.remove(pageId);
            if (change.kind() == PageChange.Kind.MISSING) {
                pages.remove(pageId);
                stale.remove(pageId);
                missing.remove(pageId);
                withheld.remove(pageId);
                RemovalCheck.Result removal = plan.removals().get(pageId);
                if (removal != null && removal.verdict() == RemovalCheck.Verdict.INACCESSIBLE) {
                    // Wie in der Inventur: sofort gesperrt, Rohdaten erst nach der Frist entfernt.
                    withheld.put(pageId, confirmedAt);
                }
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
        for (String pageId : plan.purges()) {
            pages.remove(pageId);
            stale.remove(pageId);
            missing.remove(pageId);
            withheld.remove(pageId);
        }
        if (!unknownFailures.isEmpty()) {
            throw new IllegalArgumentException("Seiten nicht im Plan: " + new TreeSet<>(unknownFailures));
        }
        missing.removeAll(pages.keySet());
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

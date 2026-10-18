package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Änderungsereignisse der Quelle (Webhooks, ADR 0014) im Katalog: Jede gemeldete Seite wird einzeln nachgefragt, dann
 * kommen nur ihre Kopfdaten in den Katalog. Eine bekannte Seite mit neuer Version gilt als veraltet
 * ({@link PageChange.Kind#OUTDATED}), eine unbekannte als nicht geladen ({@link PageChange.Kind#NOT_LOADED}); Inhalte
 * lädt nie das Ereignis, sondern die Versionsprüfung der Suche bei Bedarf.
 *
 * <ul>
 *   <li>{@link ChangeEvent.Kind#CHANGED}: eingeordnet wie im Abgleich der Betriebsart {@link SyncMode#CATALOG_ONLY};
 *       eine Meldung mit einer Version, die der Katalog schon kennt, kostet keine Anfrage.</li>
 *   <li>{@link ChangeEvent.Kind#REMOVED}: findet die Einzelabfrage die Seite nicht, ist sie gelöscht (die Quelle hat
 *       es selbst gemeldet); liegt sie in einem anderen Space, ist sie verschoben; sonst wie {@code CHANGED}.</li>
 *   <li>{@link ChangeEvent.Kind#PERMISSIONS}: eine sichtbare bekannte Seite gilt als veraltet, damit die nächste
 *       Prüfung sie neu bewertet; ist sie für das technische Konto nicht mehr sichtbar, wird sie gesperrt.</li>
 * </ul>
 *
 * <p>Ereignisse wirken nur auf Spaces mit bestätigter Generation, denn ohne Katalog gibt es nichts zu markieren; dort
 * füllt die Suche den Cache. Ein Ereignis bestätigt keine Vollständigkeit: {@link SyncBaseline#changesSince()} bleibt,
 * Nachholen und Inventur fragen weiter ab demselben Zeitpunkt. Scheitert eine Einzelabfrage, fällt das Ereignis weg;
 * Webhook-Aufrufe dürfen ausfallen, das Sicherheitsnetz sind Nachholen und Inventur.
 */
public class EventSync {

    private static final System.Logger LOG = System.getLogger(EventSync.class.getName());

    private final SourceQueries queries;
    private final SyncBaselineStore store;
    private final Clock clock;

    public EventSync(SourceQueries queries, SyncBaselineStore store, Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Der Plan für einen Space; nur Seiten, an denen sich etwas ändert.
     *
     * @param baseline die Generation, auf der er beruht
     * @param events   die Ereignisse, aus denen er entstand, für einen neuen Versuch
     */
    public record SpacePlan(SyncPlan plan, SyncBaseline baseline, List<ChangeEvent> events) {

        public SpacePlan {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(baseline, "baseline");
            events = List.copyOf(events);
        }

        public String spaceKey() {
            return plan.inventory().spaceKey();
        }
    }

    /**
     * Fragt die gemeldeten Seiten bei der Quelle nach und plant je Space; schreibt nichts.
     *
     * @return je betroffenem Space mit bestätigter Generation ein Plan, ohne Pläne ohne Änderung
     */
    public List<SpacePlan> plan(String sourceId, Collection<ChangeEvent> events) {
        Instant startedAt = clock.instant();
        Map<String, SyncBaseline> baselines = new HashMap<>();
        Map<String, Map<String, BaselineEntry>> knownBySpace = new HashMap<>();
        Map<String, List<PageChange>> changes = new LinkedHashMap<>();
        Map<String, Map<String, RemovalCheck.Result>> removals = new HashMap<>();
        Map<String, List<PageMetadataListing.Entry>> listed = new HashMap<>();
        Map<String, List<ChangeEvent>> eventsBySpace = new HashMap<>();
        boolean confirmsDeletion = queries.confirmsDeletion(sourceId);
        int dropped = 0;
        for (ChangeEvent event : events) {
            String reported = event.spaceKey() != null ? event.spaceKey()
                    : spaceOf(sourceId, event.pageId()).orElse(null);
            if (reported != null && baselines.computeIfAbsent(reported, key -> confirmed(sourceId, key)) != null) {
                Map<String, BaselineEntry> known = knownBySpace.computeIfAbsent(reported,
                        key -> known(sourceId, key, baselines.get(key)));
                if (event.kind() == ChangeEvent.Kind.CHANGED && alreadyKnown(known.get(event.pageId()), event)) {
                    continue;
                }
            }
            PageMetadataListing.Entry current;
            try {
                current = queries.pageMetadata(sourceId, event.pageId()).entry();
            } catch (SourceAccessException e) {
                if (e.reason() != SourceAccessException.Reason.NOT_FOUND || reported == null
                        || event.kind() == ChangeEvent.Kind.CHANGED) {
                    dropped++;
                    continue;
                }
                current = null;
            }
            String spaceKey = current != null ? current.page().partitionKey() : reported;
            SyncBaseline baseline = baselines.computeIfAbsent(spaceKey, key -> confirmed(sourceId, key));
            if (baseline == null && current != null && reported != null && !reported.equals(spaceKey)
                    && baselines.get(reported) != null) {
                // Aus einem erfassten Space hinaus verschoben: dort entfernen; der neue Space ist nicht erfasst.
                spaceKey = reported;
                baseline = baselines.get(reported);
            }
            if (baseline == null) {
                continue;
            }
            String key = spaceKey;
            Map<String, BaselineEntry> known = knownBySpace.computeIfAbsent(key, k -> known(sourceId, k,
                    baselines.get(k)));
            BaselineEntry previous = known.get(event.pageId());
            PageChange change;
            RemovalCheck.Result removal = null;
            if (current == null || !key.equals(current.page().partitionKey())) {
                if (previous == null && !baseline.withheldPageIds().containsKey(event.pageId())
                        && !baseline.missingPageIds().contains(event.pageId())) {
                    continue;
                }
                RemovalCheck.Verdict verdict = current != null ? RemovalCheck.Verdict.MOVED_OUT
                        : event.kind() == ChangeEvent.Kind.REMOVED || confirmsDeletion ? RemovalCheck.Verdict.DELETED
                        : RemovalCheck.Verdict.INACCESSIBLE;
                change = new PageChange(event.pageId(), PageChange.Kind.MISSING, previous, null);
                removal = new RemovalCheck.Result(event.pageId(), verdict, current, null);
            } else {
                change = SyncPlanner.within(SyncMode.CATALOG_ONLY, SyncPlanner.classify(previous, current));
                if (event.kind() == ChangeEvent.Kind.PERMISSIONS && previous != null
                        && change.kind() != PageChange.Kind.OUTDATED) {
                    change = new PageChange(event.pageId(), PageChange.Kind.OUTDATED, previous, current);
                }
                if (change.kind() == PageChange.Kind.UNCHANGED) {
                    continue;
                }
                listed.computeIfAbsent(key, k -> new ArrayList<>()).add(current);
            }
            List<PageChange> spaceChanges = changes.computeIfAbsent(key, k -> new ArrayList<>());
            spaceChanges.removeIf(earlier -> earlier.pageId().equals(event.pageId()));
            spaceChanges.add(change);
            Map<String, RemovalCheck.Result> spaceRemovals = removals.computeIfAbsent(key, k -> new HashMap<>());
            spaceRemovals.remove(event.pageId());
            if (removal != null) {
                spaceRemovals.put(event.pageId(), removal);
            }
            eventsBySpace.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        }
        if (dropped > 0) {
            LOG.log(System.Logger.Level.INFO, "Änderungsereignisse von {0}: {1} nicht nachprüfbar, verworfen",
                    sourceId, dropped);
        }
        List<SpacePlan> plans = new ArrayList<>();
        Instant finishedAt = clock.instant();
        changes.forEach((spaceKey, spaceChanges) -> {
            spaceChanges.sort(Comparator.comparing(PageChange::pageId));
            Inventory inventory = new Inventory(sourceId, spaceKey, Inventory.Status.CHANGES, null,
                    listed.getOrDefault(spaceKey, List.of()), startedAt, finishedAt);
            SyncBaseline baseline = baselines.get(spaceKey);
            plans.add(new SpacePlan(new SyncPlan(inventory, baseline, spaceChanges,
                    removals.getOrDefault(spaceKey, Map.of())), baseline, eventsBySpace.get(spaceKey)));
        });
        return plans;
    }

    /**
     * Schreibt den Plan als nächste Generation; schreibt nur Kopfdaten, entfernt oder sperrt.
     *
     * @throws SyncConflictException wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    public SpaceSync.SyncRun commit(SpacePlan spacePlan, PlanApplier applier) {
        SyncPlan plan = spacePlan.plan();
        try (StagedGeneration staged = store.begin(plan.inventory().sourceId(), spacePlan.spaceKey())) {
            applier.apply(plan, staged, SyncListener.NONE);
            SyncBaseline next = next(spacePlan.baseline(), plan);
            staged.commit(next);
            return new SpaceSync.SyncRun(plan, java.util.Set.of(), next, null);
        }
    }

    /** Die Generation, fortgeschrieben um die Ereignisse; der Zeitpunkt für das Nachholen bleibt. */
    private SyncBaseline next(SyncBaseline baseline, SyncPlan plan) {
        SortedMap<String, BaselineEntry> pages = new TreeMap<>(baseline.pages());
        SortedSet<String> stale = new TreeSet<>(baseline.stalePageIds());
        SortedSet<String> missing = new TreeSet<>(baseline.missingPageIds());
        SortedMap<String, Instant> withheld = new TreeMap<>(baseline.withheldPageIds());
        Instant now = clock.instant();
        for (PageChange change : plan.changes()) {
            String pageId = change.pageId();
            if (change.kind() == PageChange.Kind.MISSING) {
                RemovalCheck.Result removal = plan.removals().get(pageId);
                pages.remove(pageId);
                stale.remove(pageId);
                missing.remove(pageId);
                if (removal != null && removal.verdict() == RemovalCheck.Verdict.INACCESSIBLE) {
                    withheld.putIfAbsent(pageId, now);
                } else {
                    withheld.remove(pageId);
                }
                continue;
            }
            withheld.remove(pageId);
            switch (change.kind()) {
                case NOT_LOADED -> {
                    if (!pages.containsKey(pageId)) {
                        missing.add(pageId);
                    }
                }
                case OUTDATED -> {
                    pages.put(pageId, change.previous());
                    stale.add(pageId);
                }
                default -> {
                    // Kopfdaten ohne neue Version: der Stand der Seite bleibt gültig.
                    pages.put(pageId, BaselineEntry.of(change.current()));
                    missing.remove(pageId);
                }
            }
        }
        missing.removeAll(pages.keySet());
        return new SyncBaseline(baseline.sourceId(), baseline.spaceKey(), baseline.generation() + 1, now, pages,
                stale, missing, withheld, baseline.changesSince());
    }

    private SyncBaseline confirmed(String sourceId, String spaceKey) {
        return store.confirmed(sourceId, spaceKey).orElse(null);
    }

    private Map<String, BaselineEntry> known(String sourceId, String spaceKey, SyncBaseline baseline) {
        Map<String, BaselineEntry> known = new HashMap<>(baseline == null ? Map.of() : baseline.pages());
        known.putAll(store.knownPages(sourceId, spaceKey));
        return known;
    }

    /** Der Space einer Meldung ohne Space: der erfasste Space, der die Seite kennt. */
    private Optional<String> spaceOf(String sourceId, String pageId) {
        for (SyncBaselineStore.SpaceRef space : store.confirmedSpaces()) {
            if (!space.sourceId().equals(sourceId)) {
                continue;
            }
            SyncBaseline baseline = confirmed(sourceId, space.spaceKey());
            if (baseline != null && (baseline.pages().containsKey(pageId)
                    || baseline.missingPageIds().contains(pageId) || baseline.withheldPageIds().containsKey(pageId)
                    || store.knownPages(sourceId, space.spaceKey()).containsKey(pageId))) {
                return Optional.of(space.spaceKey());
            }
        }
        return Optional.empty();
    }

    /** Ob der Katalog diese oder eine neuere Version schon führt. */
    private static boolean alreadyKnown(BaselineEntry known, ChangeEvent event) {
        if (known == null || known.version() == null || event.version() == null) {
            return false;
        }
        try {
            return Long.parseLong(known.version()) >= Long.parseLong(event.version());
        } catch (NumberFormatException e) {
            return known.version().equals(event.version());
        }
    }
}

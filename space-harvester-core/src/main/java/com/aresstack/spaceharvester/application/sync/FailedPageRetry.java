package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * „Nur Fehler neu versuchen“ (ADR 0025): lädt genau die genannten Seiten nach, ohne Inventur, auf der bestätigten
 * Generation des Space. Jede Seite wird einzeln nachgefragt (Kopfdaten), dann ihr Inhalt geladen wie im Abgleich
 * ({@link PlanApplier}); das Ergebnis wird die nächste Generation, in der alle übrigen Seiten bleiben, wie sie sind.
 *
 * <ul>
 *   <li>Meldet die Quelle eine Seite nicht mehr ({@link SourceAccessException.Reason#NOT_FOUND}) oder liegt sie in
 *       einem anderen Space, fällt sie weg; ob sie gelöscht, verschoben oder gesperrt ist, entscheidet die nächste
 *       Inventur.</li>
 *   <li>Seiten, die {@code allowed} nach ihren frischen Kopfdaten nicht zulässt (nicht lesbar, außerhalb des Umfangs),
 *       fallen still weg: Sie werden weder geladen noch gezählt.</li>
 *   <li>Scheitert die Nachfrage aus anderem Grund, bleibt die Seite ungeprüft ({@link Result#unchecked()}); nach
 *       {@link PlanApplier#connectionLostLimit()} solchen Fehlern nacheinander wegen fehlender Verbindung oder
 *       Drosselung meldet der Lauf {@link SyncListener#connectionLost()}.</li>
 *   <li>Scheitert ein Inhaltsabruf, behält die Generation den bisherigen Stand der Seite als veraltet oder führt sie
 *       weiter als fehlend, wie nach einem Abgleich.</li>
 * </ul>
 */
public class FailedPageRetry {

    private final SourceQueries queries;
    private final SyncBaselineStore store;
    private final Clock clock;

    public FailedPageRetry(SourceQueries queries, SyncBaselineStore store, Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Ergebnis eines Nachladens.
     *
     * @param run         der bestätigte Lauf, oder {@code null}, wenn keine Seite zu laden war
     * @param failedPages frische Kopfdaten der zulässigen Seiten, deren Inhaltsabruf scheiterte
     * @param unchecked   Seiten, deren Kopfdaten die Quelle nicht lieferte (Verbindung, Drosselung, Fehler)
     */
    public record Result(SpaceSync.SyncRun run, List<PageHeader> failedPages, Set<String> unchecked) {

        public Result {
            failedPages = List.copyOf(failedPages);
            unchecked = Set.copyOf(unchecked);
        }
    }

    /**
     * Lädt die Seiten nach und bestätigt die nächste Generation.
     *
     * @return leer, wenn der Space keine bestätigte Generation hat
     * @throws SyncConflictException wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    public Optional<Result> run(String sourceId, String spaceKey, Collection<String> pageIds,
                                Predicate<PageHeader> allowed, PlanApplier applier, SyncListener listener) {
        Objects.requireNonNull(allowed, "allowed");
        SyncBaseline baseline = store.confirmed(sourceId, spaceKey).orElse(null);
        if (baseline == null) {
            return Optional.empty();
        }
        Instant startedAt = clock.instant();
        List<PageChange> changes = new ArrayList<>();
        List<PageMetadataListing.Entry> listed = new ArrayList<>();
        Map<String, PageHeader> headers = new HashMap<>();
        Set<String> unchecked = new TreeSet<>();
        int unreachable = 0;
        for (String pageId : new TreeSet<>(pageIds)) {
            listener.checkpoint();
            PageMetadataListing.Entry current;
            try {
                current = queries.pageMetadata(sourceId, pageId).entry();
                unreachable = 0;
            } catch (SourceAccessException e) {
                if (e.reason() == SourceAccessException.Reason.NOT_FOUND) {
                    unreachable = 0;
                    continue;
                }
                unchecked.add(pageId);
                if (e.reason() != SourceAccessException.Reason.UNAVAILABLE
                        && e.reason() != SourceAccessException.Reason.RATE_LIMITED) {
                    unreachable = 0;
                } else if (++unreachable >= applier.connectionLostLimit()) {
                    unreachable = 0;
                    listener.connectionLost();
                }
                continue;
            }
            if (!spaceKey.equals(current.page().partitionKey()) || !pageId.equals(current.page().pageId())) {
                continue;
            }
            PageHeader header = new PageHeader(new PageKey(sourceId, pageId), current.page(), current.metadataHash(),
                    current.attributesHash(), startedAt);
            if (!allowed.test(header)) {
                continue;
            }
            // Immer laden: Die Seite fehlt oder ist veraltet, auch wenn ihre Version schon bekannt ist.
            BaselineEntry previous = baseline.pages().get(pageId);
            changes.add(new PageChange(pageId, previous == null ? PageChange.Kind.NEW
                    : PageChange.Kind.CONTENT_CHANGED, previous, current));
            listed.add(current);
            headers.put(pageId, header);
        }
        if (changes.isEmpty()) {
            return Optional.of(new Result(null, List.of(), unchecked));
        }
        Inventory inventory = new Inventory(sourceId, spaceKey, Inventory.Status.CHANGES, null, listed, startedAt,
                clock.instant());
        SyncPlan plan = new SyncPlan(inventory, baseline, changes, Map.of());
        Set<String> failed;
        SyncBaseline next;
        try (StagedGeneration staged = store.begin(sourceId, spaceKey)) {
            failed = applier.apply(plan, staged, listener);
            next = next(baseline, changes, failed);
            staged.commit(next);
        }
        List<PageHeader> failedPages = new ArrayList<>();
        new TreeSet<>(failed).forEach(pageId -> failedPages.add(headers.get(pageId)));
        return Optional.of(new Result(new SpaceSync.SyncRun(plan, failed, next, null), failedPages, unchecked));
    }

    /** Die Generation mit den nachgeladenen Seiten; alles andere, auch der Zeitpunkt für das Nachholen, bleibt. */
    private SyncBaseline next(SyncBaseline baseline, List<PageChange> changes, Set<String> failed) {
        SortedMap<String, BaselineEntry> pages = new TreeMap<>(baseline.pages());
        SortedSet<String> stale = new TreeSet<>(baseline.stalePageIds());
        SortedSet<String> missing = new TreeSet<>(baseline.missingPageIds());
        SortedMap<String, Instant> withheld = new TreeMap<>(baseline.withheldPageIds());
        for (PageChange change : changes) {
            String pageId = change.pageId();
            if (failed.contains(pageId)) {
                // Bisheriger Stand bleibt veraltet, eine neue Seite fehlt weiter.
                if (!pages.containsKey(pageId)) {
                    missing.add(pageId);
                }
                continue;
            }
            pages.put(pageId, BaselineEntry.of(change.current()));
            stale.remove(pageId);
            missing.remove(pageId);
            withheld.remove(pageId);
        }
        Instant now = clock.instant();
        Instant confirmedAt = now.isBefore(baseline.confirmedAt()) ? baseline.confirmedAt() : now;
        return new SyncBaseline(baseline.sourceId(), baseline.spaceKey(), baseline.generation() + 1, confirmedAt,
                pages, stale, missing, withheld, baseline.changesSince());
    }
}

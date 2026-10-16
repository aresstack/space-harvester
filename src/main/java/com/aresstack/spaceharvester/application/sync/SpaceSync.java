package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ablauf eines Sync-Laufs für einen Space, ohne Inhalte selbst zu laden.
 *
 * <ol>
 *   <li>{@link #plan}: bestätigte Generation lesen, vollständig inventarisieren, abgleichen und fehlende Seiten
 *       einzeln nachprüfen (nur bei bestätigter Inventur).</li>
 *   <li>Der Aufrufer (Job, #12) lädt Inhalte für {@link SyncPlan#contentToFetch()}, aktualisiert Metadaten und
 *       Attribute und entfernt bestätigt fehlende Seiten aus dem Cache.</li>
 *   <li>{@link #confirm}: daraus die nächste Generation bilden und veröffentlichen.</li>
 * </ol>
 *
 * <p>Ein abgebrochener Lauf veröffentlicht nichts; die bisherige Generation bleibt gültig, und ein neuer Lauf beginnt
 * von vorn. Ein zweiter Lauf über einen unveränderten Space findet nur {@link PageChange.Kind#UNCHANGED}.
 */
public class SpaceSync {

    private final SyncBaselineStore store;
    private final InventoryCollector collector;
    private final RemovalCheck removalCheck;
    private final Clock clock;

    public SpaceSync(SourceQueries queries, SyncBaselineStore store, Clock clock) {
        this(store, new InventoryCollector(queries, clock), new RemovalCheck(queries), clock);
    }

    SpaceSync(SyncBaselineStore store, InventoryCollector collector, RemovalCheck removalCheck, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.removalCheck = Objects.requireNonNull(removalCheck, "removalCheck");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SyncPlan plan(String sourceId, String spaceKey) {
        SyncBaseline baseline = store.confirmed(sourceId, spaceKey).orElse(null);
        SyncPlan plan = SyncPlanner.plan(baseline, collector.collect(sourceId, spaceKey));
        if (!plan.inventory().confirmed()) {
            return plan;
        }
        Map<String, RemovalCheck.Result> removals = removalCheck.verify(plan);
        return removals.isEmpty() ? plan : plan.withRemovals(removals);
    }

    /**
     * @param failedPageIds Seiten, deren Abgleich fehlgeschlagen ist
     * @throws UnconfirmableSyncException siehe {@link SyncPlan#nextBaseline}
     * @throws SyncConflictException      wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    public SyncBaseline confirm(SyncPlan plan, Set<String> failedPageIds) {
        SyncBaseline next = plan.nextBaseline(clock.instant(), failedPageIds);
        store.publish(next);
        return next;
    }

    /**
     * Ganzer Lauf: planen, mit {@code applier} anwenden und, wenn möglich, die nächste Generation bestätigen. Auch eine
     * abgebrochene Inventur aktualisiert die gelesenen Seiten; sie bestätigt nur keine Generation und entfernt nichts.
     *
     * @throws SyncConflictException wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    public SyncRun run(String sourceId, String spaceKey, PlanApplier applier) {
        SyncPlan plan = plan(sourceId, spaceKey);
        Set<String> failed = applier.apply(plan);
        try {
            return new SyncRun(plan, failed, confirm(plan, failed), null);
        } catch (UnconfirmableSyncException e) {
            return new SyncRun(plan, failed, null, e.getMessage());
        }
    }

    /**
     * Ergebnis von {@link #run}.
     *
     * @param plan          der angewendete Plan
     * @param failedPageIds Seiten, deren Abgleich fehlgeschlagen ist
     * @param confirmed     die neu bestätigte Generation oder {@code null}
     * @param notConfirmed  warum keine Generation bestätigt wurde, sonst {@code null}
     */
    public record SyncRun(SyncPlan plan, Set<String> failedPageIds, SyncBaseline confirmed, String notConfirmed) {

        public SyncRun {
            failedPageIds = Set.copyOf(failedPageIds);
        }
    }
}

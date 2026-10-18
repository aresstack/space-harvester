package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ablauf eines Sync-Laufs für einen Space.
 *
 * <ol>
 *   <li>{@link #plan}: bestätigte Generation lesen, vollständig inventarisieren, abgleichen und fehlende Seiten
 *       einzeln nachprüfen (nur bei bestätigter Inventur).</li>
 *   <li>{@link #run}: Inhalte laden, Metadaten und Attribute aktualisieren und bestätigt fehlende Seiten entfernen,
 *       alles vorgemerkt und erst zusammen mit der nächsten Generation sichtbar.</li>
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
     * Bestätigt die nächste Generation ohne eigene Schreibvorgänge im Cache, etwa wenn nichts zu laden war. Läufe mit
     * Inhalten gehen über {@link #run}, damit ihre Änderungen erst mit der Generation sichtbar werden.
     *
     * @param failedPageIds Seiten, deren Abgleich fehlgeschlagen ist
     * @throws UnconfirmableSyncException siehe {@link SyncPlan#nextBaseline}
     * @throws SyncConflictException      wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    SyncBaseline confirm(SyncPlan plan, Set<String> failedPageIds) {
        SyncBaseline next = plan.nextBaseline(clock.instant(), failedPageIds);
        try (StagedGeneration staged = store.begin(plan.inventory().sourceId(), plan.inventory().spaceKey())) {
            staged.commit(next);
        }
        return next;
    }

    /**
     * Ganzer Lauf: planen, die Änderungen mit {@code applier} in einer {@link StagedGeneration} vormerken und sie mit
     * der nächsten Generation in einem Schritt sichtbar machen.
     *
     * <p>Kann der Plan keine Generation werden (abgebrochene oder widersprüchliche Inventur, übersehene Seite), wird
     * nichts geladen und nichts geschrieben. Wirft das Laden oder der Commit (z. B. {@link SyncConflictException}),
     * wird verworfen, was vorgemerkt war; der Cache bleibt beim Stand der bisherigen Generation.
     *
     * @throws SyncConflictException wenn inzwischen ein anderer Lauf eine Generation bestätigt hat
     */
    public SyncRun run(String sourceId, String spaceKey, PlanApplier applier) {
        return run(sourceId, spaceKey, applier, SyncListener.NONE);
    }

    /** Wie {@link #run(String, String, PlanApplier)}, mit Rückmeldungen und Abbruchstellen für einen Auftrag. */
    public SyncRun run(String sourceId, String spaceKey, PlanApplier applier, SyncListener listener) {
        SyncPlan plan = plan(sourceId, spaceKey);
        listener.inventoried(plan.inventory());
        Optional<String> unconfirmable = plan.unconfirmable();
        if (unconfirmable.isPresent()) {
            return new SyncRun(plan, Set.of(), null, unconfirmable.get());
        }
        try (StagedGeneration staged = store.begin(sourceId, spaceKey)) {
            Set<String> failed = applier.apply(plan, staged, listener);
            SyncBaseline next = plan.nextBaseline(clock.instant(), failed);
            listener.checkpoint();
            staged.commit(next);
            return new SyncRun(plan, failed, next, null);
        }
    }

    /**
     * Ergebnis von {@link #run}.
     *
     * @param plan          der Plan
     * @param failedPageIds Seiten, deren Abgleich fehlgeschlagen ist
     * @param confirmed     die neu bestätigte Generation oder {@code null}
     * @param notConfirmed  warum keine Generation bestätigt und nichts geschrieben wurde, sonst {@code null}
     */
    public record SyncRun(SyncPlan plan, Set<String> failedPageIds, SyncBaseline confirmed, String notConfirmed) {

        public SyncRun {
            failedPageIds = Set.copyOf(failedPageIds);
        }

        public SyncSummary summary() {
            return SyncSummary.of(this);
        }
    }
}

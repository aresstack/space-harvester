package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.time.Clock;
import java.time.Duration;
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
 *   <li>{@link #run}: Inhalte laden, Metadaten und Attribute aktualisieren, bestätigt gelöschte Seiten entfernen,
 *       nicht mehr sichtbare sperren und gesperrte nach Ablauf der Frist entfernen (ADR 0014), alles vorgemerkt und
 *       erst zusammen mit der nächsten Generation sichtbar.</li>
 * </ol>
 *
 * <p>Ein abgebrochener Lauf veröffentlicht nichts; die bisherige Generation bleibt gültig, und ein neuer Lauf beginnt
 * von vorn. Ein zweiter Lauf über einen unveränderten Space findet nur {@link PageChange.Kind#UNCHANGED}.
 */
public class SpaceSync {

    /** Standardfrist, nach der ein Lauf die Rohdaten einer gesperrten Seite entfernt (ADR 0014). */
    public static final Duration DEFAULT_WITHHELD_RETENTION = Duration.ofDays(30);

    private final SyncBaselineStore store;
    private final InventoryCollector collector;
    private final RemovalCheck removalCheck;
    private final Clock clock;
    private final Duration withheldRetention;

    public SpaceSync(SourceQueries queries, SyncBaselineStore store, Clock clock) {
        this(queries, store, clock, DEFAULT_WITHHELD_RETENTION);
    }

    /**
     * @param withheldRetention wie lange eine gesperrte Seite ({@link RemovalCheck.Verdict#INACCESSIBLE}) ihre
     *                          Rohdaten behält, bevor ein Lauf sie endgültig entfernt; {@code 0} entfernt sie im
     *                          nächsten Lauf
     */
    public SpaceSync(SourceQueries queries, SyncBaselineStore store, Clock clock, Duration withheldRetention) {
        this(store, new InventoryCollector(queries, clock), new RemovalCheck(queries), clock, withheldRetention);
    }

    SpaceSync(SyncBaselineStore store, InventoryCollector collector, RemovalCheck removalCheck, Clock clock) {
        this(store, collector, removalCheck, clock, DEFAULT_WITHHELD_RETENTION);
    }

    SpaceSync(SyncBaselineStore store, InventoryCollector collector, RemovalCheck removalCheck, Clock clock,
              Duration withheldRetention) {
        this.store = Objects.requireNonNull(store, "store");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.removalCheck = Objects.requireNonNull(removalCheck, "removalCheck");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(withheldRetention, "withheldRetention");
        if (withheldRetention.isNegative()) {
            throw new IllegalArgumentException("Frist für gesperrte Seiten darf nicht negativ sein");
        }
        this.withheldRetention = withheldRetention;
    }

    public SyncPlan plan(String sourceId, String spaceKey) {
        return plan(sourceId, spaceKey, true);
    }

    /**
     * Plant einen Lauf. Bekannt ist, was die Generation führt und was der Cache kennt (ADR 0014, Punkt 2): Seiten, die
     * Suche oder Abruf schon mit derselben Version abgelegt haben, werden nicht erneut geladen.
     *
     * @param loadUnknown ob gelistete, aber unbekannte Seiten geladen werden ({@link SyncMode#loadingUnknown})
     *                    gehalten); sonst {@link PageChange.Kind#NOT_LOADED}
     */
    public SyncPlan plan(String sourceId, String spaceKey, boolean loadUnknown) {
        return plan(sourceId, spaceKey, SyncMode.loadingUnknown(loadUnknown));
    }

    /** Wie {@link #plan(String, String, boolean)}, in der Betriebsart {@code mode}. */
    public SyncPlan plan(String sourceId, String spaceKey, SyncMode mode) {
        SyncBaseline baseline = store.confirmed(sourceId, spaceKey).orElse(null);
        Inventory inventory = collector.collect(sourceId, spaceKey);
        SyncPlan plan = SyncPlanner.plan(baseline, inventory,
                inventory.confirmed() ? store.knownPages(sourceId, spaceKey) : Map.of(), mode);
        if (!plan.inventory().confirmed()) {
            return plan;
        }
        Map<String, RemovalCheck.Result> removals = removalCheck.verify(plan);
        SyncPlan checked = removals.isEmpty() ? plan : plan.withRemovals(removals);
        return checked.withPurgesDue(clock.instant().minus(withheldRetention));
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
        return run(sourceId, spaceKey, applier, listener, true);
    }

    /**
     * Wie {@link #run(String, String, PlanApplier, SyncListener)}; ohne {@code loadUnknown} ein Abgleich ohne
     * Erstbefüllung (ADR 0014): Er prüft und aktualisiert nur, was Generation und Cache kennen, und führt unbekannte
     * Seiten als fehlend.
     */
    public SyncRun run(String sourceId, String spaceKey, PlanApplier applier, SyncListener listener,
                       boolean loadUnknown) {
        return run(sourceId, spaceKey, applier, listener, SyncMode.loadingUnknown(loadUnknown));
    }

    /** Wie {@link #run(String, String, PlanApplier, SyncListener, boolean)}, in der Betriebsart {@code mode}. */
    public SyncRun run(String sourceId, String spaceKey, PlanApplier applier, SyncListener listener, SyncMode mode) {
        SyncPlan plan = plan(sourceId, spaceKey, mode);
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

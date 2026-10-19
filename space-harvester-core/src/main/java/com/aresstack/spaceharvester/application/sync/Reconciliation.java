package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Optionaler Zeitplan je Space (ADR 0014): startet in einem festen Abstand das
 * {@link SpaceSyncJobs#catchUp Nachholen der Änderungen} oder, wenn der Space vollständig gehalten werden soll, einen
 * {@link SpaceSyncJobs#start(String, String, String, boolean) Abgleich mit Inventur}. Kein Vollabruf der Inhalte und
 * kein eigener Löschpfad; Hauptweg für Änderungen sind Ereignisse der Quelle.
 *
 * <p>Standardmäßig ist kein Space eingeplant; ohne Plan behauptet der Core keine lückenlose Aktualität. Läuft für einen
 * Space noch ein Abgleich, wird der fällige übersprungen. Die Einplanung gilt, solange dieses Objekt lebt; den
 * dauerhaften Laufzustand führt die bestätigte Generation.
 */
public final class Reconciliation implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Reconciliation.class.getName());

    /** Kürzester Abstand, damit ein Fehlgriff in der Einstellung die Quelle nicht im Sekundentakt inventarisiert. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    /** Ergebnis des letzten fälligen Starts. */
    public enum Outcome {
        /** Ein Abgleich wurde gestartet; {@link Schedule#lastJobId()} nennt ihn. */
        STARTED,
        /** Für den Space lief noch ein Abgleich. */
        ALREADY_RUNNING,
        /** Die Auftragsverwaltung nahm keinen weiteren Auftrag an. */
        NO_CAPACITY,
        /** Nicht startbar, etwa unbekannte oder abgeschaltete Quelle, fehlender Cache. */
        NOT_STARTED
    }

    /**
     * Einplanung eines Space.
     *
     * @param sourceId     Quellen-ID
     * @param spaceKey     Space-Key
     * @param interval     Abstand zwischen zwei Starts
     * @param mode         Betriebsart der Läufe; {@link SyncMode#FULL} hält den Space vollständig
     * @param nextAt       nächster fälliger Start
     * @param lastAt       letzter fälliger Start oder {@code null}
     * @param lastOutcome  sein Ergebnis oder {@code null}
     * @param lastJobId    der zuletzt gestartete Abgleich oder {@code null}; Zustand und Delta über
     *                     {@link SpaceSyncJobs#status}
     */
    public record Schedule(String sourceId, String spaceKey, Duration interval, SyncMode mode, Instant nextAt,
                           Instant lastAt, Outcome lastOutcome, String lastJobId) {

        /** Ob die Läufe auch unbekannte Seiten laden ({@link SyncMode#FULL}). */
        public boolean loadUnknown() {
            return mode.loadsUnknown();
        }
    }

    private final SpaceSyncJobs syncs;
    private final String owner;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    /** Nur für Inventuren: Bezugspunkt je Space über Neustarts hinweg; sonst {@code null}. */
    private final InventoryLog inventory;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** @param owner Auftraggeber der gestarteten Abgleiche oder {@code null} für einen Hintergrundabgleich */
    public Reconciliation(SpaceSyncJobs syncs, String owner, Clock clock) {
        this(syncs, owner, clock, Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("reconciliation").factory()));
    }

    Reconciliation(SpaceSyncJobs syncs, String owner, Clock clock, ScheduledExecutorService scheduler) {
        this(syncs, owner, clock, scheduler, null);
    }

    private Reconciliation(SpaceSyncJobs syncs, String owner, Clock clock, ScheduledExecutorService scheduler,
                           InventoryLog inventory) {
        this.inventory = inventory;
        this.syncs = Objects.requireNonNull(syncs, "syncs");
        this.owner = owner;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Ein Zeitplan für die regelmäßige Bestandsinventur ({@code sync.inventory-interval}, ADR 0014 Punkt 1): Jeder
     * Termin startet einen {@link SpaceSyncJobs#start(String, String, String, SyncMode) Abgleich mit Inventur}, der die
     * Metadaten des ganzen Space listet und so auch Löschungen und Verschiebungen findet, statt nur die Änderungen
     * nachzuholen. Die Betriebsart gilt wie beim Nachholen, {@link SyncMode#FULL} höchstens als
     * {@link SyncMode#SYNC_CACHE}: Eine Inventur lädt nie den ganzen Space.
     *
     * <p>Fällig ist eine Inventur einen Abstand nach dem Bezugspunkt in {@code log}: dem Start der letzten oder, solange
     * keine lief, dem ersten Einplanen. Neustarts und Konfigurationswechsel setzen die Uhr daher nicht zurück. Läuft
     * für den Space gerade ein Abgleich oder nimmt die Auftragsverwaltung keinen an, versucht sie es nach
     * {@link #MIN_INTERVAL} erneut, statt einen ganzen Abstand zu verlieren.
     *
     * @param owner wie im Konstruktor
     * @param log   Bezugspunkte je Space, etwa {@code FileInventoryLog} im Cache-Verzeichnis
     */
    public static Reconciliation inventories(SpaceSyncJobs syncs, String owner, Clock clock, InventoryLog log) {
        return inventories(syncs, owner, clock, log, Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("inventory").factory()));
    }

    static Reconciliation inventories(SpaceSyncJobs syncs, String owner, Clock clock, InventoryLog log,
                                      ScheduledExecutorService scheduler) {
        return new Reconciliation(syncs, owner, clock, scheduler, Objects.requireNonNull(log, "log"));
    }

    /**
     * Plant den regelmäßigen Abgleich eines Space ein oder ersetzt seine Einplanung; der erste Start folgt nach
     * {@code interval}.
     *
     * @throws IllegalArgumentException bei einem Abstand unter {@link #MIN_INTERVAL}
     */
    public synchronized Schedule schedule(String sourceId, String spaceKey, Duration interval, boolean loadUnknown) {
        return schedule(sourceId, spaceKey, interval, SyncMode.loadingUnknown(loadUnknown));
    }

    /**
     * Wie {@link #schedule(String, String, Duration, boolean)}: {@link SyncMode#FULL} startet je Termin eine Inventur,
     * die alles lädt; sonst holt jeder Termin die Änderungen in der Betriebsart nach.
     */
    public synchronized Schedule schedule(String sourceId, String spaceKey, Duration interval, SyncMode mode) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(interval, "interval");
        if (interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("Abstand des Abgleichs muss mindestens " + MIN_INTERVAL + " sein");
        }
        cancel(sourceId, spaceKey);
        Instant now = clock.instant();
        Instant first = now.plus(interval);
        if (inventory != null) {
            Optional<Instant> since = inventory.since(sourceId, spaceKey);
            if (since.isEmpty()) {
                markQuietly(sourceId, spaceKey, now);
            } else if (since.get().plus(interval).isBefore(first)) {
                // Überfällige Inventuren starten kurz nach dem Einplanen, nicht alle im selben Augenblick.
                first = since.get().plus(interval).isAfter(now) ? since.get().plus(interval) : now.plus(CATCH_UP);
            }
        }
        Entry entry = new Entry(sourceId, spaceKey, interval, mode, first);
        entries.put(key(sourceId, spaceKey), entry);
        arm(entry);
        return entry.view();
    }

    /** Verzögerung einer beim Einplanen schon überfälligen Inventur. */
    static final Duration CATCH_UP = Duration.ofSeconds(30);

    /** Plant den nächsten Start zu {@code nextAt}, solange der Eintrag eingeplant ist. */
    private synchronized void arm(Entry entry) {
        if (entries.get(key(entry.sourceId, entry.spaceKey)) != entry || scheduler.isShutdown()) {
            return;
        }
        long delay = Math.max(0, Duration.between(clock.instant(), entry.view().nextAt()).toMillis());
        try {
            entry.future = scheduler.schedule(() -> {
                due(entry);
                arm(entry);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // geschlossen
        }
    }

    /** Nimmt den Space aus der Einplanung; ein laufender Abgleich läuft zu Ende. */
    public synchronized boolean cancel(String sourceId, String spaceKey) {
        Entry removed = entries.remove(key(sourceId, spaceKey));
        if (removed == null) {
            return false;
        }
        if (removed.future != null) {
            removed.future.cancel(false);
        }
        return true;
    }

    /** Alle Einplanungen, nach Quelle und Space. */
    public List<Schedule> schedules() {
        return entries.values().stream().map(Entry::view)
                .sorted(Comparator.comparing(Schedule::sourceId).thenComparing(Schedule::spaceKey))
                .toList();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** Ein fälliger Start; package-sichtbar für Tests ohne Wartezeit. */
    void due(Entry entry) {
        Outcome outcome;
        String jobId = entry.lastJobId;
        try {
            // Ohne Erstbefüllung genügt das Nachholen der Änderungen, wo die Quelle sie meldet (ADR 0014).
            JobStatus started;
            if (inventory != null) {
                started = syncs.start(entry.sourceId, entry.spaceKey, owner,
                        entry.mode == SyncMode.FULL ? SyncMode.SYNC_CACHE : entry.mode);
            } else {
                started = entry.mode == SyncMode.FULL
                        ? syncs.start(entry.sourceId, entry.spaceKey, owner, SyncMode.FULL)
                        : syncs.catchUp(entry.sourceId, entry.spaceKey, owner, null, entry.mode);
            }
            outcome = Outcome.STARTED;
            jobId = started.id();
        } catch (SyncAlreadyRunningException e) {
            outcome = Outcome.ALREADY_RUNNING;
        } catch (JobCapacityExceededException e) {
            outcome = Outcome.NO_CAPACITY;
        } catch (RuntimeException e) {
            outcome = Outcome.NOT_STARTED;
            LOG.log(System.Logger.Level.WARNING, "Regelmäßiger Abgleich {0}/{1} nicht gestartet ({2})",
                    entry.sourceId, entry.spaceKey, e.getClass().getName());
        }
        Instant now = clock.instant();
        Duration next = entry.interval;
        if (inventory != null) {
            if (outcome == Outcome.STARTED) {
                markQuietly(entry.sourceId, entry.spaceKey, now);
            } else if (outcome == Outcome.ALREADY_RUNNING || outcome == Outcome.NO_CAPACITY) {
                // Ein laufendes Nachholen ersetzt keine Inventur: bald erneut versuchen.
                next = MIN_INTERVAL;
            }
        }
        synchronized (entry) {
            entry.lastAt = now;
            entry.lastOutcome = outcome;
            entry.lastJobId = jobId;
            entry.nextAt = now.plus(next);
        }
    }

    private void markQuietly(String sourceId, String spaceKey, Instant at) {
        try {
            inventory.mark(sourceId, spaceKey, at);
        } catch (RuntimeException e) {
            // Weder Start noch Konfigurationswechsel scheitern daran; ohne Vermerk zählt der Abstand nach einem
            // Neustart ab dem Einplanen.
            LOG.log(System.Logger.Level.WARNING, "Zeitpunkt der Bestandsinventur nicht gespeichert ({0})",
                    e.getClass().getName());
        }
    }

    Entry entry(String sourceId, String spaceKey) {
        return entries.get(key(sourceId, spaceKey));
    }

    private static String key(String sourceId, String spaceKey) {
        return sourceId + "/" + spaceKey;
    }

    static final class Entry {

        private final String sourceId;
        private final String spaceKey;
        private final Duration interval;
        private final SyncMode mode;
        private ScheduledFuture<?> future;
        private Instant nextAt;
        private Instant lastAt;
        private Outcome lastOutcome;
        private String lastJobId;

        private Entry(String sourceId, String spaceKey, Duration interval, SyncMode mode, Instant nextAt) {
            this.sourceId = sourceId;
            this.spaceKey = spaceKey;
            this.interval = interval;
            this.mode = mode;
            this.nextAt = nextAt;
        }

        private synchronized Schedule view() {
            return new Schedule(sourceId, spaceKey, interval, mode, nextAt, lastAt, lastOutcome, lastJobId);
        }
    }
}

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Regelmäßiger Abgleich je Space (ADR 0014, Punkt 1): startet in einem festen Abstand einen gewöhnlichen
 * {@link SpaceSyncJobs#start(String, String, String, boolean) Abgleich}, der die Metadaten inventarisiert, nur
 * geänderte Inhalte lädt und Fehlendes einzeln prüft. Kein Vollabruf der Inhalte und kein eigener Löschpfad.
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
     * @param loadUnknown  ob der Space vollständig gehalten wird, also auch unbekannte Seiten geladen werden
     * @param nextAt       nächster fälliger Start
     * @param lastAt       letzter fälliger Start oder {@code null}
     * @param lastOutcome  sein Ergebnis oder {@code null}
     * @param lastJobId    der zuletzt gestartete Abgleich oder {@code null}; Zustand und Delta über
     *                     {@link SpaceSyncJobs#status}
     */
    public record Schedule(String sourceId, String spaceKey, Duration interval, boolean loadUnknown, Instant nextAt,
                           Instant lastAt, Outcome lastOutcome, String lastJobId) {
    }

    private final SpaceSyncJobs syncs;
    private final String owner;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** @param owner Auftraggeber der gestarteten Abgleiche oder {@code null} für einen Hintergrundabgleich */
    public Reconciliation(SpaceSyncJobs syncs, String owner, Clock clock) {
        this(syncs, owner, clock, Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("reconciliation").factory()));
    }

    Reconciliation(SpaceSyncJobs syncs, String owner, Clock clock, ScheduledExecutorService scheduler) {
        this.syncs = Objects.requireNonNull(syncs, "syncs");
        this.owner = owner;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Plant den regelmäßigen Abgleich eines Space ein oder ersetzt seine Einplanung; der erste Start folgt nach
     * {@code interval}.
     *
     * @throws IllegalArgumentException bei einem Abstand unter {@link #MIN_INTERVAL}
     */
    public synchronized Schedule schedule(String sourceId, String spaceKey, Duration interval, boolean loadUnknown) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(spaceKey, "spaceKey");
        Objects.requireNonNull(interval, "interval");
        if (interval.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("Abstand des Abgleichs muss mindestens " + MIN_INTERVAL + " sein");
        }
        cancel(sourceId, spaceKey);
        Entry entry = new Entry(sourceId, spaceKey, interval, loadUnknown, clock.instant().plus(interval));
        long millis = interval.toMillis();
        entry.future = scheduler.scheduleWithFixedDelay(() -> due(entry), millis, millis, TimeUnit.MILLISECONDS);
        entries.put(key(sourceId, spaceKey), entry);
        return entry.view();
    }

    /** Nimmt den Space aus der Einplanung; ein laufender Abgleich läuft zu Ende. */
    public synchronized boolean cancel(String sourceId, String spaceKey) {
        Entry removed = entries.remove(key(sourceId, spaceKey));
        if (removed == null) {
            return false;
        }
        removed.future.cancel(false);
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
            JobStatus started = syncs.start(entry.sourceId, entry.spaceKey, owner, entry.loadUnknown);
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
        synchronized (entry) {
            entry.lastAt = now;
            entry.lastOutcome = outcome;
            entry.lastJobId = jobId;
            entry.nextAt = now.plus(entry.interval);
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
        private final boolean loadUnknown;
        private ScheduledFuture<?> future;
        private Instant nextAt;
        private Instant lastAt;
        private Outcome lastOutcome;
        private String lastJobId;

        private Entry(String sourceId, String spaceKey, Duration interval, boolean loadUnknown, Instant nextAt) {
            this.sourceId = sourceId;
            this.spaceKey = spaceKey;
            this.interval = interval;
            this.loadUnknown = loadUnknown;
            this.nextAt = nextAt;
        }

        private synchronized Schedule view() {
            return new Schedule(sourceId, spaceKey, interval, loadUnknown, nextAt, lastAt, lastOutcome, lastJobId);
        }
    }
}

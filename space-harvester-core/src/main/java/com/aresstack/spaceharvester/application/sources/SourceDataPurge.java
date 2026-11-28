package com.aresstack.spaceharvester.application.sources;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * „Daten bereinigen“: löscht alle lokal gespeicherten Daten genau einer Quelle, ohne die Quelle selbst zu entfernen.
 * Andere Quellen bleiben unberührt; Einstellungen, Zugangsdaten und der Schalter Ein/Aus der Quelle bleiben. Danach ist
 * der Bestand der Quelle leer und baut sich mit dem nächsten Abgleich oder Download neu auf.
 *
 * <p>Welche Ablagen dazugehören, legt das einbettende Programm fest ({@link Store}); jede entfernt nur, was an der
 * {@code sourceId} hängt. Vorher brechen die übrigen Aufträge der Quelle ab, damit kein laufender Abgleich nach dem
 * Löschen wieder etwas ablegt. Scheitert eine Ablage, laufen die übrigen weiter; der Auftrag endet dann als
 * gescheitert und nennt die betroffenen Ablagen, nie Inhalte.
 *
 * <p>Rechte: Wie {@code SourceManagement} prüft die Bereinigung keine {@code AccessPolicy}; das tut der Aufrufer.
 */
public final class SourceDataPurge {

    /** Komponente in Ereignissen und Phasen des Auftrags. */
    public static final String COMPONENT = "purge";

    /** Fehlercode des Auftrags, wenn eine Ablage nicht geleert werden konnte. */
    public static final String INCOMPLETE = "purge-incomplete";

    /**
     * Parameter eines Auftrags, der die Daten einer entfernten oder auf ein neues Ziel umgestellten Quelle löscht
     * (Fundstelle 22): {@code removed} oder {@code moved}.
     */
    public static final String REASON = "purge.reason";
    /** Wert von {@link #REASON}: Die Quelle wurde entfernt. */
    public static final String REMOVED = "removed";
    /** Wert von {@link #REASON}: Die Quelle zeigt auf ein anderes Ziel. */
    public static final String MOVED = "moved";

    /** So lange wartet die Bereinigung auf laufende Aufträge der Quelle. */
    public static final Duration JOB_STOP_TIMEOUT = Jobs.STOP_GRACE;

    private static final System.Logger LOG = System.getLogger(SourceDataPurge.class.getName());

    /**
     * Eine Ablage, die Daten je Quelle hält.
     *
     * @param name   für Benutzer, etwa „Seiten-Cache und Katalog“; erscheint als Phase des Auftrags
     * @param remove entfernt alles der übergebenen Quelle und nichts anderes
     */
    public record Store(String name, Consumer<String> remove) {

        public Store {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(remove, "remove");
        }
    }

    /** Ergebnis eines Laufs: die geleerten und die gescheiterten Ablagen, je mit {@link Store#name()}. */
    public record Outcome(List<String> cleared, List<String> failed) {

        public Outcome {
            cleared = List.copyOf(cleared);
            failed = List.copyOf(failed);
        }

        public boolean complete() {
            return failed.isEmpty();
        }
    }

    /**
     * Hält Arbeit an, die außerhalb der Aufträge Daten der Quelle ablegt (Beschaffung, Hintergrundfüllung), bis die
     * Rückgabe geschlossen wird; wartet auf laufende Abrufe der Quelle.
     */
    @FunctionalInterface
    public interface Quiesce {

        Quiesce NONE = sourceId -> () -> { };

        AutoCloseable quiesce(String sourceId) throws Exception;

        /**
         * Erst dieses, dann {@code next} anhalten; freigegeben in umgekehrter Reihenfolge. Scheitert {@code next}, wird
         * dieses gleich wieder freigegeben.
         */
        default Quiesce andThen(Quiesce next) {
            Objects.requireNonNull(next, "next");
            return sourceId -> {
                AutoCloseable first = quiesce(sourceId);
                AutoCloseable second;
                try {
                    second = next.quiesce(sourceId);
                } catch (Exception | Error e) {
                    try {
                        first.close();
                    } catch (Exception suppressed) {
                        e.addSuppressed(suppressed);
                    }
                    throw e;
                }
                return () -> {
                    try (first) {
                        second.close();
                    }
                };
            };
        }
    }

    /** Abstände der Selbstheilung eines gescheiterten Löschens; danach bleibt es beim letzten. */
    public static final List<Duration> HEAL_DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofMinutes(30));

    private final Jobs jobs;
    private final List<Store> stores;
    private final Predicate<String> configured;
    private final Quiesce quiesce;
    /** Der jüngste Auftrag aus {@link #startWithdrawal} je Quelle; gepflegt aus den Auftragsmeldungen. */
    private final java.util.Map<String, JobStatus> withdrawals = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> healAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Consumer<String> withdrawn = sourceId -> { };
    private volatile List<Duration> healDelays = HEAL_DELAYS;

    /**
     * @param configured ob eine Quelle eingerichtet ist, eingeschaltet oder abgeschaltet
     */
    public SourceDataPurge(Jobs jobs, List<Store> stores, Predicate<String> configured) {
        this(jobs, stores, configured, Quiesce.NONE);
    }

    /**
     * @param quiesce hält während des Löschens Beschaffung und Hintergrundfüllung der Quelle an
     */
    public SourceDataPurge(Jobs jobs, List<Store> stores, Predicate<String> configured, Quiesce quiesce) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.stores = List.copyOf(stores);
        this.configured = Objects.requireNonNull(configured, "configured");
        this.quiesce = Objects.requireNonNull(quiesce, "quiesce");
        jobs.subscribeAll(new com.aresstack.spaceharvester.application.jobs.JobListener() {
            @Override
            public void changed(JobStatus status) {
                SourceDataPurge.this.changed(status);
            }

            @Override
            public void removed(JobStatus last) {
                if (last.sourceId() != null) {
                    withdrawals.computeIfPresent(last.sourceId(),
                            (sourceId, known) -> known.id().equals(last.id()) ? null : known);
                }
            }
        });
        for (JobStatus status : jobs.listAll(JobType.PURGE)) {
            note(status);
        }
        // Eine Wahrheit für Halten, Sperre und Löschschutz: das offene Löschen je Quelle (Abnahme zu Fundstelle 22).
        jobs.sourceHold(this::openWithdrawal);
    }

    /** Die Ablagen in der Reihenfolge, in der sie geleert werden. */
    public List<String> stores() {
        return stores.stream().map(Store::name).toList();
    }

    /**
     * Startet die Bereinigung als Auftrag {@link JobType#PURGE} mit Fortschritt je Ablage. Auch eine abgeschaltete
     * Quelle lässt sich bereinigen. Wartende Aufträge der Quelle brechen sofort ab, laufende an ihrem nächsten
     * Prüfpunkt; der Auftrag wartet darauf, bevor er löscht.
     *
     * @param owner auslösender Client, siehe {@link JobStatus#owner()}
     * @throws UnknownSourceException ohne eingerichtete Quelle dieser ID
     */
    public JobStatus start(String sourceId, String owner) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!configured.test(sourceId)) {
            throw new UnknownSourceException(sourceId);
        }
        String open = openWithdrawal(sourceId);
        if (open != null) {
            // Die alten Daten werden schon gelöscht; ein zweites Bereinigen bräche dieses Löschen ab.
            throw new SourceRemovalPendingException(sourceId, open);
        }
        // Gibt Plätze frei, bevor der Auftrag sie braucht; gewartet wird erst im Auftrag.
        stopJobs(sourceId, null, Duration.ZERO);
        return jobs.submit(JobType.PURGE, sourceId, null, owner, task(sourceId));
    }

    /**
     * Löscht die Daten einer eben entfernten oder auf ein neues Ziel umgestellten Quelle als Auftrag (Fundstelle 22,
     * Angelo 21.11.2026: nichts läuft unsichtbar), statt im aufrufenden Thread. Prüft nicht, ob die Quelle eingerichtet
     * ist. Der Auftrag läuft im Core weiter, auch wenn die App neu lädt; endet der Dienst vorher, setzt
     * {@link #resumePending()} ihn beim nächsten Start im selben Eintrag fort. Scheitert er, wiederholt er sich von
     * selbst ({@link #HEAL_DELAYS}), bis er gelingt.
     *
     * <p>Solange das Löschen offen ist ({@link #openWithdrawal}), auch angehalten, unterbrochen oder gescheitert, hält es
     * die Quelle ({@link Jobs#sourceHold}): Jeder andere Auftrag der Quelle startet erst danach, und das Löschen beendet
     * nur Aufträge, die vor ihm angenommen wurden.
     *
     * @param reason {@link #REMOVED} oder {@link #MOVED}
     */
    public JobStatus startWithdrawal(String sourceId, String owner, String reason) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(reason, "reason");
        stopJobs(sourceId, null, Duration.ZERO);
        java.util.Map<String, String> parameters = new java.util.HashMap<>();
        parameters.put(REASON, reason);
        parameters.put(Jobs.SURVIVES_SHUTDOWN, "true");
        JobStatus started = jobs.submit(JobType.PURGE, sourceId, null, null, owner, parameters, task(sourceId));
        note(started);
        jobs.holdsChanged();
        return started;
    }

    /**
     * Ob die Daten einer entfernten Quelle noch gelöscht werden: der jüngste Auftrag aus {@link #startWithdrawal} mit
     * {@link #REMOVED}, solange er nicht gelungen und nicht vom Benutzer abgebrochen ist, also auch gescheitert,
     * unterbrochen oder angehalten. Solange lässt sich dieselbe ID nicht neu anlegen. Liest nur den Speicher, den die
     * Auftragsmeldungen pflegen (ADR 0027), nie die Auftragsablage; darf auf dem EDT laufen.
     */
    public java.util.Optional<JobStatus> withdrawing(String sourceId) {
        return java.util.Optional.ofNullable(withdrawals.get(sourceId))
                .filter(status -> REMOVED.equals(status.parameter(REASON)) && pending(status));
    }

    /**
     * Die Quellen, deren Daten noch gelöscht werden, je mit ihrem jüngsten Auftrag aus {@link #startWithdrawal}
     * ({@link #withdrawing}); aus dem Speicher.
     */
    public java.util.Map<String, JobStatus> withdrawals() {
        java.util.Map<String, JobStatus> result = new java.util.TreeMap<>();
        withdrawals.forEach((sourceId, status) -> {
            if (REMOVED.equals(status.parameter(REASON)) && pending(status)) {
                result.put(sourceId, status);
            }
        });
        return result;
    }

    /**
     * Der Auftrag, der die Daten der Quelle gerade löscht (aus {@link #startWithdrawal}, entfernt oder umgestellt), von
     * der Annahme bis er gelingt oder der Benutzer ihn abbricht, auch angehalten, unterbrochen oder gescheitert mit
     * ausstehender Selbstheilung; sonst {@code null}. Aus dem Speicher.
     */
    public String openWithdrawal(String sourceId) {
        JobStatus status = sourceId == null ? null : withdrawals.get(sourceId);
        return status != null && pending(status) ? status.id() : null;
    }

    /** Die Quellen, deren Löschen gerade offen ist ({@link #openWithdrawal}); aus dem Speicher. */
    public java.util.Set<String> openWithdrawals() {
        java.util.Set<String> open = new java.util.HashSet<>();
        withdrawals.forEach((sourceId, status) -> {
            if (pending(status)) {
                open.add(sourceId);
            }
        });
        return open;
    }

    /**
     * Wird aufgerufen, sobald das Löschen einer Quelle aus {@link #startWithdrawal} nicht mehr offen ist: gelungen
     * oder vom Benutzer abgebrochen.
     */
    public void onSettled(Consumer<String> listener) {
        this.withdrawn = Objects.requireNonNull(listener, "listener");
    }

    /**
     * „Fortsetzen“ in den Aufträgen: lässt einen angehaltenen, gescheiterten oder unterbrochenen Auftrag
     * „Daten bereinigen“ im selben Eintrag von vorn laufen; schon geleerte Ablagen bleiben leer.
     */
    public JobStatus resume(String jobId, String owner) {
        JobStatus current = jobs.status(jobId, owner);
        if (current.type() != JobType.PURGE) {
            throw new com.aresstack.spaceharvester.application.jobs.UnknownJobException(jobId);
        }
        String open = openWithdrawal(current.sourceId());
        if (open != null && !open.equals(jobId)) {
            throw new SourceRemovalPendingException(current.sourceId(), open);
        }
        // Nur was vor dem Löschen kam: Was danach kam, wartet hinter ihm (Abnahme Punkt 1).
        try {
            jobs.cancelSource(current.sourceId(), jobId, current.createdAt(), Duration.ZERO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        JobStatus resumed = jobs.rerun(jobId, owner, task(current.sourceId()));
        note(resumed);
        jobs.holdsChanged();
        return resumed;
    }

    /**
     * Beim Start: setzt das Löschen jeder Quelle, deren jüngster Auftrag aus {@link #startWithdrawal} unterbrochen oder
     * gescheitert ist, im selben Eintrag fort; ein angehaltener bleibt angehalten, bis sein Besitzer fortsetzt. Ältere
     * offene Einträge derselben Quelle werden abgelöst ({@link Jobs#retire}), damit keiner später wieder auflebt. Auch
     * eine entfernte Quelle, die inzwischen wieder eingerichtet ist, wird erst zu Ende gelöscht.
     *
     * @return die fortgesetzten Aufträge
     */
    public List<JobStatus> resumePending() {
        List<JobStatus> resumed = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<JobStatus> all = new ArrayList<>(jobs.listAll(JobType.PURGE));
        all.sort(java.util.Comparator.comparing(JobStatus::createdAt).reversed());
        for (JobStatus status : all) {
            if (status.parameter(REASON) == null || status.sourceId() == null) {
                continue;
            }
            if (!seen.add(status.sourceId())) {
                if (status.state() == JobState.FAILED || status.state() == JobState.PAUSED) {
                    try {
                        jobs.retire(status.id(), status.owner(), "durch jüngeres Löschen derselben Quelle abgelöst");
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.DEBUG, "Älteres Löschen nicht abgelöst ({0})",
                                e.getClass().getName());
                    }
                }
                continue;
            }
            if (status.state() == JobState.FAILED) {
                try {
                    resumed.add(resume(status.id(), status.owner()));
                    LOG.log(System.Logger.Level.INFO, "Löschen der Daten von Quelle {0} wird fortgesetzt",
                            status.sourceId());
                } catch (RuntimeException e) {
                    // Bleibt gescheitert und offen stehen; die Selbstheilung versucht es weiter (Abnahme Punkt 4).
                    LOG.log(System.Logger.Level.WARNING, "Löschen der Daten von Quelle {0} nicht fortgesetzt ({1})",
                            status.sourceId(), e.getClass().getName());
                    heal(status);
                }
            }
        }
        return resumed;
    }

    /** Merkt den jüngsten Stand des Löschens je Quelle (nur Aufträge aus {@link #startWithdrawal}). */
    private void note(JobStatus status) {
        if (status.type() != JobType.PURGE || status.parameter(REASON) == null || status.sourceId() == null) {
            return;
        }
        withdrawals.merge(status.sourceId(), status, (known, fresh) -> known.id().equals(fresh.id())
                || !fresh.createdAt().isBefore(known.createdAt()) ? fresh : known);
    }

    /** Auftragsmeldung: hält den Speicher aktuell, meldet Erfolge und heilt Fehlschläge. */
    private void changed(JobStatus status) {
        if (status.type() != JobType.PURGE || status.parameter(REASON) == null || status.sourceId() == null) {
            return;
        }
        note(status);
        JobStatus latest = withdrawals.get(status.sourceId());
        if (latest == null || !latest.id().equals(status.id())) {
            return;
        }
        if (status.state().terminal()) {
            jobs.holdsChanged();
        }
        if (status.state() == JobState.CANCELLED) {
            healAttempts.remove(status.id());
            settled(status.sourceId());
        } else if (status.state() == JobState.COMPLETED) {
            healAttempts.remove(status.id());
            settled(status.sourceId());
        } else if (status.state() == JobState.FAILED && !interrupted(status)) {
            heal(status);
        }
    }

    private void settled(String sourceId) {
        try {
            withdrawn.accept(sourceId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Nach dem Löschen der Daten von Quelle {0}: {1}", sourceId,
                    e.getClass().getName());
        }
    }

    /**
     * Selbstheilung: wiederholt ein gescheitertes Löschen nach {@link #HEAL_DELAYS} im selben Eintrag, bis es gelingt;
     * „Fortsetzen“ in den Aufträgen geht jederzeit früher. Ein unterbrochenes setzt der nächste Start fort.
     */
    private void heal(JobStatus failed) {
        int attempt = healAttempts.merge(failed.id(), 1, Integer::sum);
        List<Duration> delays = healDelays;
        Duration delay = delays.get(Math.min(attempt, delays.size()) - 1);
        LOG.log(System.Logger.Level.WARNING, "Löschen der Daten von Quelle {0} gescheitert ({1}); neuer Versuch in {2} s",
                failed.sourceId(), failed.error() == null ? "?" : failed.error().code(), delay.toSeconds());
        java.util.concurrent.CompletableFuture.delayedExecutor(delay.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            JobStatus latest = withdrawals.get(failed.sourceId());
            if (latest == null || !latest.id().equals(failed.id()) || latest.state() != JobState.FAILED) {
                return;
            }
            try {
                resume(failed.id(), failed.owner());
            } catch (IllegalStateException e) {
                // Der Dienst wird beendet: der nächste Start setzt fort.
                LOG.log(System.Logger.Level.INFO, "Löschen der Daten von Quelle {0} beim Beenden nicht wiederholt",
                        failed.sourceId());
            } catch (RuntimeException e) {
                // Warteschlange voll oder Auftrag noch nicht freigegeben: nächster Versuch (Abnahme Punkt 4).
                LOG.log(System.Logger.Level.WARNING, "Löschen der Daten von Quelle {0} nicht wiederholt ({1})",
                        failed.sourceId(), e.getClass().getName());
                heal(failed);
            }
        });
    }

    /** Für Tests: kürzere Abstände der Selbstheilung. */
    void healDelays(List<Duration> delays) {
        if (delays.isEmpty()) {
            throw new IllegalArgumentException("delays");
        }
        this.healDelays = List.copyOf(delays);
    }

    private static boolean pending(JobStatus status) {
        return status.state() != JobState.COMPLETED && status.state() != JobState.CANCELLED;
    }

    private static boolean interrupted(JobStatus status) {
        return status.state() == JobState.FAILED && status.error() != null
                && com.aresstack.spaceharvester.application.jobs.JobLifecycleState.INTERRUPTED.equals(
                        status.error().code());
    }

    private JobTask task(String sourceId) {
        return JobTask.blocking(context -> {
            context.discovered(stores.size());
            context.phase(COMPONENT, "Aufträge der Quelle beenden");
            // Nur was vor diesem Auftrag kam: Was danach kam, wartet hinter ihm (Jobs#sourceHold).
            java.time.Instant before = jobs.status(context.jobId()).createdAt();
            while (!jobs.cancelSource(sourceId, context.jobId(), before, JOB_STOP_TIMEOUT)) {
                // Laufende Aufträge brauchen länger: weiter warten statt scheitern; Abbrechen geht.
                context.checkpoint();
                context.phase(COMPONENT, "Wartet weiter auf laufende Aufträge der Quelle");
            }
            context.checkpoint();
            context.phase(COMPONENT, "Beschaffung der Quelle anhalten");
            Outcome outcome;
            try (AutoCloseable paused = quiesce.quiesce(sourceId)) {
                outcome = run(sourceId, context);
            }
            if (!outcome.complete()) {
                throw new JobFailedException(INCOMPLETE, "Nicht geleert: " + String.join(", ", outcome.failed()));
            }
        });
    }

    /**
     * Bereinigt sofort im aufrufenden Thread, etwa beim Entfernen einer Quelle; bricht vorher die Aufträge der Quelle
     * ab. Prüft nicht, ob die Quelle eingerichtet ist.
     */
    public Outcome purge(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!stopJobs(sourceId, null, JOB_STOP_TIMEOUT)) {
            LOG.log(System.Logger.Level.WARNING, "Aufträge der Quelle {0} laufen nach {1} s noch",
                    sourceId, JOB_STOP_TIMEOUT.toSeconds());
        }
        AutoCloseable paused = null;
        try {
            paused = quiesce.quiesce(sourceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Nur das Anhalten scheiterte; gelöscht wird trotzdem.
            LOG.log(System.Logger.Level.WARNING, "Beschaffung der Quelle {0} nicht angehalten ({1})", sourceId,
                    e.getClass().getName());
        }
        try {
            return run(sourceId, null);
        } finally {
            if (paused != null) {
                try {
                    paused.close();
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING, "Beschaffung der Quelle {0} nicht freigegeben ({1})",
                            sourceId, e.getClass().getName());
                }
            }
        }
    }

    private Outcome run(String sourceId, JobContext context) {
        List<String> cleared = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int done = 0;
        for (Store store : stores) {
            if (context != null) {
                // Abbruch und Laufzeitgrenze gelten zwischen den Ablagen; die schon geleerten bleiben leer.
                context.checkpoint();
                context.phase(COMPONENT, store.name());
            }
            try {
                store.remove().accept(sourceId);
                cleared.add(store.name());
                if (context != null) {
                    context.written(1);
                }
            } catch (RuntimeException e) {
                failed.add(store.name());
                LOG.log(System.Logger.Level.WARNING, "Daten der Quelle {0} in {1} nicht entfernt ({2})", sourceId,
                        store.name(), e.getClass().getName());
                if (context != null) {
                    context.failed(1);
                    context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, INCOMPLETE,
                            store.name() + " nicht geleert (" + e.getClass().getSimpleName() + ")"));
                }
            }
            done++;
            if (context != null) {
                context.processed(1);
            }
        }
        LOG.log(System.Logger.Level.INFO, "Daten der Quelle {0} bereinigt ({1} von {2} Ablagen)", sourceId,
                cleared.size(), done);
        return new Outcome(cleared, failed);
    }

    private boolean stopJobs(String sourceId, String exceptJobId, Duration timeout) {
        try {
            return jobs.cancelSource(sourceId, exceptJobId, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

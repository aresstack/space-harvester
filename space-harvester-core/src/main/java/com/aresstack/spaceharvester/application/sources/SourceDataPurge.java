package com.aresstack.spaceharvester.application.sources;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
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

    /** So lange wartet die Bereinigung auf laufende Aufträge der Quelle. */
    public static final Duration JOB_STOP_TIMEOUT = Duration.ofSeconds(30);

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
    }

    private final Jobs jobs;
    private final List<Store> stores;
    private final Predicate<String> configured;
    private final Quiesce quiesce;

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
        // Gibt Plätze frei, bevor der Auftrag sie braucht; gewartet wird erst im Auftrag.
        stopJobs(sourceId, null, Duration.ZERO);
        return jobs.submit(JobType.PURGE, sourceId, null, owner, JobTask.blocking(context -> {
            context.discovered(stores.size());
            context.phase(COMPONENT, "Aufträge der Quelle beenden");
            if (!stopJobs(sourceId, context.jobId(), JOB_STOP_TIMEOUT)) {
                throw new JobFailedException("jobs-still-running", "Aufträge der Quelle laufen nach "
                        + JOB_STOP_TIMEOUT.toSeconds() + " s noch; nichts gelöscht");
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
        }));
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

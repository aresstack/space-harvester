package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sync-Läufe als Aufträge ({@link JobType#SYNC}): starten, Status mit Delta, Abbruch. Je Space läuft höchstens ein
 * Abgleich; ein zweiter würde nur am Generationswechsel scheitern.
 *
 * <p>Ausgang eines Auftrags: {@link JobState#COMPLETED} nur mit bestätigter Generation. Kann keine bestätigt werden
 * (Inventur abgebrochen oder widersprüchlich), endet er als {@link JobState#FAILED} mit Code {@code SYNC_INCOMPLETE};
 * hat ein anderer Lauf zwischenzeitlich bestätigt, mit {@code SYNC_CONFLICT} und ohne Delta, weil sein Plan auf einer
 * überholten Generation beruht. Fehlt einer neuen Seite der Inhalt, weil ihr erster Abruf scheiterte, ist die
 * Generation bestätigt, aber unvollständig ({@link SyncBaseline#complete()}); scheitert der Abgleich einer bekannten
 * Seite, bleibt ihr alter Stand als veraltet. In beiden Fällen endet der Auftrag als {@link JobState#FAILED} mit
 * {@code SYNC_PARTIAL}, nie als erfolgreicher Abgleich. Das Delta ({@link SyncSummary}) steht sonst nach dem Lauf bereit. Ein Abbruch verwirft
 * alles Vorgemerkte.
 *
 * <p>Fehlen Cache-Speicher oder Inhaltsabruf (noch nicht angebunden), lehnt {@link #start} mit
 * {@link SyncUnavailableException} ab, statt einen Auftrag anzunehmen, der nur scheitern kann.
 */
public class SpaceSyncJobs {

    static final String INCOMPLETE = "SYNC_INCOMPLETE";
    static final String CONFLICT = "SYNC_CONFLICT";
    static final String PARTIAL = "SYNC_PARTIAL";

    private static final System.Logger LOG = System.getLogger(SpaceSyncJobs.class.getName());

    private final Jobs jobs;
    private final SourceQueries queries;
    private final SpaceSync sync;
    private final PlanApplier applier;
    private final Map<String, String> activeBySpace = new ConcurrentHashMap<>();
    private final Map<String, SyncSummary> summaries = new ConcurrentHashMap<>();

    /**
     * @param store   Cache-Speicher oder {@code null}, solange keiner angebunden ist
     * @param fetcher Inhaltsabruf oder {@code null}, solange keiner angebunden ist
     */
    public SpaceSyncJobs(Jobs jobs, SourceQueries queries, SyncBaselineStore store, PageContentFetcher fetcher,
                         Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.sync = store == null || fetcher == null ? null : new SpaceSync(queries, store, clock);
        this.applier = fetcher == null ? null : new PlanApplier(fetcher);
    }

    public boolean available() {
        return sync != null;
    }

    /**
     * Startet einen Abgleich des Space, ohne auf ihn zu warten.
     *
     * @param owner auslösender Client oder {@code null} für den geplanten Hintergrundabgleich
     * @throws UnknownSourceException      wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SyncUnavailableException     wenn Cache oder Inhaltsabruf nicht angebunden sind
     * @throws SyncAlreadyRunningException  wenn für den Space bereits ein Abgleich wartet oder läuft
     * @throws com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException wenn keine Aufträge mehr
     *                                      angenommen werden
     */
    public synchronized JobStatus start(String sourceId, String spaceKey, String owner) {
        if (queries.sources().stream().noneMatch(source -> source.id().equals(sourceId))) {
            throw new UnknownSourceException(sourceId);
        }
        if (!available()) {
            throw new SyncUnavailableException("Cache-Speicher oder Inhaltsabruf sind nicht angebunden");
        }
        String space = sourceId + "/" + spaceKey;
        String running = activeBySpace.get(space);
        if (running != null && !finished(running)) {
            throw new SyncAlreadyRunningException(running);
        }
        JobStatus status = jobs.submit(JobType.SYNC, sourceId, spaceKey, owner,
                JobTask.blocking(context -> run(context, sourceId, spaceKey)));
        activeBySpace.put(space, status.id());
        return status;
    }

    /**
     * Status und, sobald der Lauf fertig ist, sein Delta; nur für den eigenen Auftrag.
     *
     * @throws UnknownJobException wenn es keinen Sync-Auftrag mit dieser ID für diesen Client gibt
     */
    public SyncJob status(String jobId, String owner) {
        return view(jobs.status(jobId, owner));
    }

    /** @throws UnknownJobException wie {@link #status} */
    public SyncJob cancel(String jobId, String owner) {
        view(jobs.status(jobId, owner));
        return view(jobs.cancel(jobId, owner));
    }

    /**
     * Ein Sync-Auftrag.
     *
     * @param status  Zustand und Zähler des Auftrags
     * @param summary Delta des Laufs, sobald er eines hat, sonst {@code null}
     */
    public record SyncJob(JobStatus status, SyncSummary summary) {
    }

    private SyncJob view(JobStatus status) {
        if (status.type() != JobType.SYNC) {
            throw new UnknownJobException(status.id());
        }
        if (status.state().terminal()) {
            summaries.keySet().removeIf(id -> !id.equals(status.id()) && expired(id));
        }
        return new SyncJob(status, summaries.get(status.id()));
    }

    private void run(JobContext context, String sourceId, String spaceKey) {
        SpaceSync.SyncRun run;
        try {
            run = sync.run(sourceId, spaceKey, applier, listener(context));
        } catch (SyncConflictException e) {
            throw new JobFailedException(CONFLICT, "Ein anderer Abgleich hat inzwischen eine Generation bestätigt");
        }
        // Auch geplante Läufe, deren Status nie gelesen wird, sollen keine Deltas abgelaufener Aufträge halten.
        summaries.keySet().removeIf(this::expired);
        summaries.put(context.jobId(), run.summary());
        if (run.confirmed() == null) {
            // Der Grund kann Seiten-IDs oder Meldungen der Quelle enthalten: nur ins Log, nicht in den Auftrag.
            LOG.log(System.Logger.Level.INFO, "Abgleich {0}/{1}: keine Generation bestätigt ({2})", sourceId,
                    spaceKey, run.notConfirmed());
            throw new JobFailedException(INCOMPLETE, "Keine Generation bestätigt; der Cache bleibt unverändert");
        }
        if (!run.confirmed().complete() || !run.failedPageIds().isEmpty()) {
            throw new JobFailedException(PARTIAL, "Generation " + run.confirmed().generation()
                    + " bestätigt, aber " + run.failedPageIds().size() + " Seiten konnten nicht abgeglichen werden "
                    + "(davon " + run.confirmed().missingPageIds().size() + " ohne Stand im Cache); der nächste "
                    + "Lauf gleicht sie erneut ab");
        }
    }

    private static SyncListener listener(JobContext context) {
        return new SyncListener() {

            @Override
            public void inventoried(int pages) {
                context.discovered(pages);
            }

            @Override
            public void pageDone(PageChange change, boolean written, boolean failed) {
                context.processed(1);
                if (written) {
                    context.written(1);
                }
                if (failed) {
                    context.failed(1);
                }
            }

            @Override
            public void checkpoint() {
                context.checkpoint();
            }
        };
    }

    private boolean finished(String jobId) {
        try {
            return jobs.status(jobId).state().terminal();
        } catch (UnknownJobException e) {
            return true;
        }
    }

    private boolean expired(String jobId) {
        try {
            jobs.status(jobId);
            return false;
        } catch (UnknownJobException e) {
            return true;
        }
    }
}

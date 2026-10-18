package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.export.SpaceProvisioning;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

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
 *
 * <p>Als {@link SpaceProvisioning} liest derselbe Ablauf einen Space innerhalb eines Exportauftrags ein
 * ({@link #provision}), ohne eigenen Auftrag und mit derselben Folgearbeit nach dem Bestätigen.
 */
public class SpaceSyncJobs implements SpaceProvisioning {

    static final String INCOMPLETE = "SYNC_INCOMPLETE";
    static final String CONFLICT = "SYNC_CONFLICT";
    static final String PARTIAL = "SYNC_PARTIAL";

    private static final System.Logger LOG = System.getLogger(SpaceSyncJobs.class.getName());
    private static final String COMPONENT = "SpaceSync";

    private final Jobs jobs;
    private final SourceQueries queries;
    private final SpaceSync sync;
    private final ChangeSync changeSync;
    private final EventSync eventSync;
    private final SyncBaselineStore store;
    private final PlanApplier applier;
    private final List<SyncCommitted> afterCommit;
    private final Map<String, String> activeBySpace = new ConcurrentHashMap<>();
    private final Map<String, SyncSummary> summaries = new ConcurrentHashMap<>();

    /**
     * @param store   Cache-Speicher oder {@code null}, solange keiner angebunden ist
     * @param fetcher Inhaltsabruf oder {@code null}, solange keiner angebunden ist
     */
    public SpaceSyncJobs(Jobs jobs, SourceQueries queries, SyncBaselineStore store, PageContentFetcher fetcher,
                         Clock clock) {
        this(jobs, queries, store, fetcher, clock, List.of());
    }

    /**
     * @param afterCommit Folgearbeit nach jeder bestätigten Generation, der Reihe nach; ihr Scheitern wird nur
     *                    protokolliert
     */
    public SpaceSyncJobs(Jobs jobs, SourceQueries queries, SyncBaselineStore store, PageContentFetcher fetcher,
                         Clock clock, List<SyncCommitted> afterCommit) {
        this(jobs, queries, store, fetcher, clock, afterCommit, SpaceSync.DEFAULT_WITHHELD_RETENTION);
    }

    /** @param withheldRetention Frist für gesperrte Seiten, siehe {@link SpaceSync} */
    public SpaceSyncJobs(Jobs jobs, SourceQueries queries, SyncBaselineStore store, PageContentFetcher fetcher,
                         Clock clock, List<SyncCommitted> afterCommit, Duration withheldRetention) {
        this(jobs, queries, store, fetcher, clock, afterCommit, SpaceSync.fixed(withheldRetention));
    }

    /**
     * Wie oben, mit einer Frist je Quelle, die jeder Lauf neu liest (Konfiguration zur Laufzeit, ADR 0015).
     *
     * @param withheldRetention Frist für gesperrte Seiten je Quellen-ID
     */
    public SpaceSyncJobs(Jobs jobs, SourceQueries queries, SyncBaselineStore store, PageContentFetcher fetcher,
                         Clock clock, List<SyncCommitted> afterCommit,
                         Function<String, Duration> withheldRetention) {
        this.afterCommit = List.copyOf(afterCommit);
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.sync = store == null || fetcher == null ? null : new SpaceSync(queries, store, clock,
                withheldRetention);
        this.store = store;
        this.changeSync = store == null || fetcher == null ? null : new ChangeSync(queries, store, clock);
        this.eventSync = store == null || fetcher == null ? null : new EventSync(queries, store, clock);
        this.applier = fetcher == null ? null : new PlanApplier(fetcher);
    }

    @Override
    public boolean available() {
        return sync != null;
    }

    /**
     * Liest den Space im laufenden Auftrag {@code context} ein, wie es ein Abgleich täte. Hat ein gleichzeitiger
     * Abgleich zuerst bestätigt, gilt dessen Generation.
     *
     * @throws UnknownSourceException   wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SyncUnavailableException wenn Cache oder Inhaltsabruf nicht angebunden sind
     */
    @Override
    public Provisioned provision(JobContext context, String sourceId, String spaceKey) {
        return provision(context, sourceId, spaceKey, header -> false);
    }

    /**
     * Wie {@link #provision(JobContext, String, String)}; {@code discovered} und {@code processed} zählen nur Seiten,
     * für die {@code counted} gilt, {@code written} und {@code failed} gar nicht (die gehören dem Export).
     */
    @Override
    public Provisioned provision(JobContext context, String sourceId, String spaceKey,
                                 Predicate<PageHeader> counted) {
        Objects.requireNonNull(counted, "counted");
        if (queries.sources().stream().noneMatch(source -> source.id().equals(sourceId))) {
            throw new UnknownSourceException(sourceId);
        }
        if (!available()) {
            throw new SyncUnavailableException("Cache-Speicher oder Inhaltsabruf sind nicht angebunden");
        }
        SpaceSync.SyncRun run;
        try {
            context.phase(COMPONENT, "Inventur");
            run = sync.run(sourceId, spaceKey, applier, provisionListener(context, counted));
        } catch (SyncConflictException e) {
            return new Provisioned(true, true, List.of());
        }
        if (run.confirmed() == null) {
            LOG.log(System.Logger.Level.INFO, "Einlesen {0}/{1} für Auftrag {2}: keine Generation bestätigt ({3})",
                    sourceId, spaceKey, context.jobId(), run.notConfirmed());
            return Provisioned.NOT_CONFIRMED;
        }
        afterCommit(run, sourceId, spaceKey, context.owner());
        return new Provisioned(true, false, failedPages(run));
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
        return start(sourceId, spaceKey, owner, true);
    }

    /**
     * Wie {@link #start(String, String, String)}; ohne {@code loadUnknown} ein Abgleich ohne Erstbefüllung (ADR 0014):
     * Er prüft, aktualisiert und bereinigt nur, was Generation und Cache kennen, und lädt keine unbekannten Seiten. Der
     * Auftrag ist ein gewöhnlicher {@link JobType#SYNC}; nicht geladene Seiten machen ihn nicht {@code SYNC_PARTIAL},
     * die Generation führt sie aber als fehlend.
     */
    public synchronized JobStatus start(String sourceId, String spaceKey, String owner, boolean loadUnknown) {
        return start(sourceId, spaceKey, owner, SyncMode.loadingUnknown(loadUnknown));
    }

    /**
     * Wie {@link #start(String, String, String)}: eine Inventur in der Betriebsart {@code mode}, etwa
     * {@link SyncMode#CATALOG_ONLY} zum Initialisieren des Katalogs ohne Inhalte (ADR 0014).
     */
    public synchronized JobStatus start(String sourceId, String spaceKey, String owner, SyncMode mode) {
        Objects.requireNonNull(mode, "mode");
        return submit(sourceId, spaceKey, owner, context -> {
            context.phase(COMPONENT, "Inventur");
            return sync.run(sourceId, spaceKey, applier, listener(context), mode);
        });
    }

    private interface Run {
        SpaceSync.SyncRun run(JobContext context);
    }

    private JobStatus submit(String sourceId, String spaceKey, String owner, Run work) {
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
                JobTask.blocking(context -> run(context, sourceId, spaceKey, work)));
        activeBySpace.put(space, status.id());
        return status;
    }

    /**
     * Startet das Nachholen verpasster Änderungen (ADR 0014), ohne auf es zu warten: Meldet die Quelle Änderungen
     * ({@link ChangeSync#supported}), eine Änderungssuche seit {@code since} und eine Papierkorbabfrage; sonst ein
     * Abgleich ohne Erstbefüllung. Ein gewöhnlicher {@link JobType#SYNC}; kein Vollabruf der Inhalte.
     *
     * @param since ab wann, oder {@code null} für den Stand der letzten bestätigten Generation
     * @throws UnknownSourceException      wie {@link #start(String, String, String)}
     * @throws SyncUnavailableException    wie {@link #start(String, String, String)}
     * @throws SyncAlreadyRunningException wie {@link #start(String, String, String)}
     */
    public synchronized JobStatus catchUp(String sourceId, String spaceKey, String owner, Instant since) {
        return catchUp(sourceId, spaceKey, owner, since, SyncMode.SYNC_CACHE);
    }

    /**
     * Wie {@link #catchUp(String, String, String, Instant)}, in der Betriebsart {@code mode}; ohne Änderungssuche der
     * Quelle eine Inventur in derselben Betriebsart, mit {@link SyncMode#FULL} höchstens {@link SyncMode#SYNC_CACHE}, weil
     * Nachholen nie den ganzen Space lädt.
     */
    public synchronized JobStatus catchUp(String sourceId, String spaceKey, String owner, Instant since,
                                          SyncMode mode) {
        Objects.requireNonNull(mode, "mode");
        return submit(sourceId, spaceKey, owner, context -> {
            if (changeSync.supported(sourceId)) {
                context.phase(COMPONENT, "Änderungssuche");
                return changeSync.run(sourceId, spaceKey, since, applier, listener(context), mode);
            }
            context.phase(COMPONENT, "Inventur");
            return sync.run(sourceId, spaceKey, applier, listener(context),
                    mode == SyncMode.FULL ? SyncMode.SYNC_CACHE : mode);
        });
    }

    /**
     * Wendet Änderungsereignisse der Quelle an ({@link EventSync}, ADR 0014): nur Kopfdaten in den Katalog, kein
     * Inhaltsabruf, kein Auftrag. Fragt die Seiten im Aufrufer bei der Quelle nach; geschrieben wird je Space nur, wenn
     * dort gerade kein Abgleich läuft.
     *
     * @return die Ereignisse, die jetzt nicht angewendet werden konnten, weil ihr Space gerade abgeglichen wird oder ein
     *         anderer Lauf zuerst bestätigt hat; der Aufrufer versucht sie später erneut
     * @throws UnknownSourceException   wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SyncUnavailableException wenn Cache oder Inhaltsabruf nicht angebunden sind
     */
    public List<ChangeEvent> applyEvents(String sourceId, List<ChangeEvent> events) {
        Objects.requireNonNull(events, "events");
        if (queries.sources().stream().noneMatch(source -> source.id().equals(sourceId))) {
            throw new UnknownSourceException(sourceId);
        }
        if (!available()) {
            throw new SyncUnavailableException("Cache-Speicher oder Inhaltsabruf sind nicht angebunden");
        }
        List<ChangeEvent> deferred = new ArrayList<>();
        List<ChangeEvent> now = new ArrayList<>();
        for (ChangeEvent event : events) {
            // Läuft im gemeldeten Space ein Abgleich, wartet das Ereignis ohne Anfrage an die Quelle.
            (event.spaceKey() != null && running(sourceId, event.spaceKey()) ? deferred : now).add(event);
        }
        for (EventSync.SpacePlan plan : eventSync.plan(sourceId, now)) {
            SpaceSync.SyncRun run;
            synchronized (this) {
                if (running(sourceId, plan.spaceKey())) {
                    deferred.addAll(plan.events());
                    continue;
                }
                try {
                    run = eventSync.commit(plan, applier);
                } catch (SyncConflictException e) {
                    deferred.addAll(plan.events());
                    continue;
                }
            }
            afterCommit(run, sourceId, plan.spaceKey(), null);
        }
        return deferred;
    }

    /**
     * Vorschau eines Abgleichs in der Betriebsart {@code mode} ({@link SyncPreview}): Inventur und Einzelprüfungen
     * fehlender Seiten, aber kein Laden und kein Schreiben. Läuft im Aufrufer, nicht als Auftrag.
     *
     * @throws UnknownSourceException   wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws SyncUnavailableException wenn Cache oder Inhaltsabruf nicht angebunden sind
     */
    public SyncPreview preview(String sourceId, String spaceKey, SyncMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (queries.sources().stream().noneMatch(source -> source.id().equals(sourceId))) {
            throw new UnknownSourceException(sourceId);
        }
        if (!available()) {
            throw new SyncUnavailableException("Cache-Speicher oder Inhaltsabruf sind nicht angebunden");
        }
        return SyncPreview.of(sync.plan(sourceId, spaceKey, mode));
    }

    /**
     * Bestand des Space im Cache ({@link SpaceCatalog}), ohne Quellenzugriff; ohne Cache-Speicher
     * {@link SpaceCatalog.State#UNKNOWN}.
     */
    public SpaceCatalog catalog(String sourceId, String spaceKey) {
        if (store == null) {
            return SpaceCatalog.of(sourceId, spaceKey, null, 0, 0);
        }
        return SpaceCatalog.of(sourceId, spaceKey, store.confirmed(sourceId, spaceKey).orElse(null),
                store.catalogedPages(sourceId, spaceKey), store.knownPages(sourceId, spaceKey).size());
    }

    /** Alle Spaces mit bestätigter Generation ({@link SyncBaselineStore#confirmedSpaces()}). */
    public List<SyncBaselineStore.SpaceRef> confirmedSpaces() {
        return store == null ? List.of() : store.confirmedSpaces();
    }

    /**
     * Holt für jeden Space mit bestätigter Generation die verpassten Änderungen nach ({@link #catchUp}), etwa beim
     * Start (ADR 0014). Spaces, deren Quelle fehlt oder für die schon ein Abgleich läuft, werden übersprungen; ohne
     * Kapazität endet die Runde.
     *
     * @return die gestarteten Aufträge
     */
    public List<JobStatus> catchUpAll(String owner) {
        return catchUpAll(owner, SyncMode.SYNC_CACHE);
    }

    /** Wie {@link #catchUpAll(String)}, in der Betriebsart {@code mode}. */
    public List<JobStatus> catchUpAll(String owner, SyncMode mode) {
        Objects.requireNonNull(mode, "mode");
        return catchUpAll(owner, sourceId -> mode);
    }

    /**
     * Wie {@link #catchUpAll(String)}, mit einer Betriebsart je Quelle (Regeln je Quelle, ADR 0015).
     *
     * @param modeBySource Betriebsart je Quellen-ID oder {@code null}, wenn die Quelle nicht nachholen soll
     */
    public List<JobStatus> catchUpAll(String owner, Function<String, SyncMode> modeBySource) {
        Objects.requireNonNull(modeBySource, "modeBySource");
        if (!available()) {
            return List.of();
        }
        Set<String> sources = new HashSet<>();
        queries.sources().forEach(source -> sources.add(source.id()));
        List<JobStatus> started = new ArrayList<>();
        for (SyncBaselineStore.SpaceRef space : store.confirmedSpaces()) {
            SyncMode mode = sources.contains(space.sourceId()) ? modeBySource.apply(space.sourceId()) : null;
            if (mode == null) {
                continue;
            }
            try {
                started.add(catchUp(space.sourceId(), space.spaceKey(), owner, null, mode));
            } catch (SyncAlreadyRunningException e) {
                // läuft schon
            } catch (com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException e) {
                break;
            }
        }
        return started;
    }

    /**
     * Status und, sobald der Lauf fertig ist, sein Delta; nur für den eigenen Auftrag.
     *
     * @throws UnknownJobException wenn es keinen Sync-Auftrag mit dieser ID für diesen Client gibt
     */
    public SyncJob status(String jobId, String owner) {
        return view(jobs.status(jobId, owner));
    }

    /**
     * Diagnoseereignisse des Abgleichs.
     *
     * @throws UnknownJobException wie {@link #status}
     */
    public List<JobEvent> events(String jobId, String owner) {
        view(jobs.status(jobId, owner));
        return jobs.events(jobId, owner);
    }

    /** @throws UnknownJobException wie {@link #status} */
    public SyncJob cancel(String jobId, String owner) {
        view(jobs.status(jobId, owner));
        return view(jobs.cancel(jobId, owner));
    }

    /**
     * Löscht einen eigenen beendeten Abgleich samt Delta und Diagnoseereignissen.
     *
     * @throws UnknownJobException wie {@link #status}
     * @throws com.aresstack.spaceharvester.application.jobs.JobStillRunningException wenn er noch wartet oder läuft
     */
    public void delete(String jobId, String owner) {
        view(jobs.status(jobId, owner));
        jobs.delete(jobId, owner);
        summaries.remove(jobId);
    }

    /**
     * Löscht alle eigenen beendeten Abgleiche samt Ereignissen; wartende und laufende bleiben.
     *
     * @return Anzahl gelöschter Abgleiche
     */
    public int deleteFinished(String owner) {
        int removed = jobs.deleteFinished(owner, JobType.SYNC);
        summaries.keySet().removeIf(this::expired);
        return removed;
    }

    /**
     * Löscht ein Diagnoseereignis eines eigenen beendeten Abgleichs.
     *
     * @return {@code false}, wenn es das Ereignis nicht gibt
     * @throws UnknownJobException wie {@link #status}
     * @throws com.aresstack.spaceharvester.application.jobs.JobStillRunningException wenn er noch wartet oder läuft
     */
    public boolean deleteEvent(String jobId, long sequence, String owner) {
        view(jobs.status(jobId, owner));
        return jobs.deleteEvent(jobId, sequence, owner);
    }

    /**
     * Löscht die Diagnoseereignisse eines eigenen beendeten Abgleichs vor {@code before}.
     *
     * @return Anzahl gelöschter Ereignisse
     * @throws UnknownJobException wie {@link #status}
     * @throws com.aresstack.spaceharvester.application.jobs.JobStillRunningException wenn er noch wartet oder läuft
     */
    public int deleteEventsBefore(String jobId, Instant before, String owner) {
        view(jobs.status(jobId, owner));
        return jobs.deleteEventsBefore(jobId, before, owner);
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

    private void run(JobContext context, String sourceId, String spaceKey, Run work) {
        SpaceSync.SyncRun run;
        try {
            run = work.run(context);
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
        afterCommit(run, sourceId, spaceKey, context.owner());
        // Fehlende Seiten ohne Fehlschlag sind nur die bewusst nicht geladenen; jede andere fehlende ist fehlgeschlagen.
        if (!run.failedPageIds().isEmpty()) {
            throw new JobFailedException(PARTIAL, "Generation " + run.confirmed().generation()
                    + " bestätigt, aber " + run.failedPageIds().size() + " Seiten konnten nicht abgeglichen werden "
                    + "(davon " + run.confirmed().missingPageIds().size() + " ohne Stand im Cache); der nächste "
                    + "Lauf gleicht sie erneut ab");
        }
    }

    /** Kopfdaten der gescheiterten Seiten aus der Inventur, auch für Seiten, die nie in den Cache kamen. */
    private static List<PageHeader> failedPages(SpaceSync.SyncRun run) {
        Inventory inventory = run.plan().inventory();
        return inventory.pages().stream()
                .filter(entry -> run.failedPageIds().contains(entry.page().pageId()))
                .map(entry -> new PageHeader(new PageKey(inventory.sourceId(), entry.page().pageId()), entry.page(),
                        entry.metadataHash(), entry.attributesHash(), inventory.finishedAt()))
                .toList();
    }

    private void afterCommit(SpaceSync.SyncRun run, String sourceId, String spaceKey, String owner) {
        for (SyncCommitted next : afterCommit) {
            try {
                next.committed(run, owner);
            } catch (RuntimeException e) {
                // Folgearbeit ist neu berechenbar und darf den bestätigten Abgleich nicht scheitern lassen.
                LOG.log(System.Logger.Level.WARNING, "Folgearbeit nach Abgleich {0}/{1} scheiterte ({2})", sourceId,
                        spaceKey, e.getClass().getName());
            }
        }
    }

    /** Fortschritt des Einlesens im Export: nur lesbare Seiten, nur gefunden und abgeglichen. */
    private static SyncListener provisionListener(JobContext context, Predicate<PageHeader> counted) {
        Set<String> visible = ConcurrentHashMap.newKeySet();
        return new SyncListener() {

            @Override
            public void inventoried(Inventory inventory) {
                context.phase(COMPONENT, "Seiten abrufen");
                for (PageMetadataListing.Entry entry : inventory.pages()) {
                    PageHeader header = new PageHeader(new PageKey(inventory.sourceId(), entry.page().pageId()),
                            entry.page(), entry.metadataHash(), entry.attributesHash(), inventory.finishedAt());
                    if (counted.test(header)) {
                        visible.add(entry.page().pageId());
                    }
                }
                context.discovered(visible.size());
            }

            @Override
            public void pageDone(PageChange change, boolean written, boolean failed) {
                if (visible.contains(change.pageId())) {
                    context.processed(1);
                }
            }

            @Override
            public void checkpoint() {
                context.checkpoint();
            }
        };
    }

    private static SyncListener listener(JobContext context) {
        return new SyncListener() {

            @Override
            public void inventoried(int pages) {
                context.phase(COMPONENT, "Seiten abrufen");
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

    private boolean running(String sourceId, String spaceKey) {
        String running = activeBySpace.get(sourceId + "/" + spaceKey);
        return running != null && !finished(running);
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

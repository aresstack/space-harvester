package com.aresstack.spaceharvester.application.jobs;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Nimmt Aufträge an, führt höchstens {@link JobLimits#maxConcurrent()} gleichzeitig aus und hält ihren Zustand fest.
 *
 * <p>Ein Auftrag ist sofort nach {@link #submit} als {@link JobState#QUEUED} abrufbar; der Aufrufer wartet nie auf die
 * Arbeit. Abbruch ist kooperativ: ein wartender Auftrag endet sofort, ein laufender beim nächsten
 * {@link JobContext#checkpoint()}. Nach einem Neustart gelten Aufträge, die laut {@link JobStore} noch warteten oder
 * liefen, als gescheitert ({@code INTERRUPTED}); sie werden nicht stillschweigend fortgesetzt. Beendete Aufträge
 * bleiben {@link JobLimits#retention()} lang abrufbar und werden danach beim Start und bei jeder Annahme entfernt.
 *
 * <p>Zu jedem Auftrag hält {@code Jobs} Diagnoseereignisse fest ({@link JobEvent}, docs/DIAGNOSTICS.md): Annahme,
 * Start, Phasenwechsel, Meldungen des Auftrags und das Ende, beim Scheitern mit Komponente, Phase, Fehlercode,
 * HTTP-Status, Proxy-Route, TLS-Befund und der maskierten Ausnahmekette. Die Ereignisse leben so lange wie ihr Auftrag.
 *
 * <p>Der Auftraggeber kann seine Aufträge in jedem Zustand löschen ({@link #delete(String, String)},
 * {@link #deleteAll}); wartende und laufende bricht das vorher ab (ADR 0025). Ereignisse löscht er nur bei beendeten
 * Aufträgen ({@link #deleteEvent}, {@link #deleteEventsBefore(Instant, String)}, sonst
 * {@link JobStillRunningException}).
 *
 * <p>Erreicht ein laufender Auftrag nach einem angeforderten Abbruch oder Anhalten {@link #STOP_GRACE} lang keinen
 * {@link JobContext#checkpoint()}, etwa weil ein Abruf der Quelle hängt, gilt er als abgebrochen bzw. angehalten und
 * gibt seinen Platz frei; die hängende Arbeit wird abgekoppelt: sie meldet nichts mehr, ändert den Eintrag nicht mehr
 * und endet an ihrem nächsten Prüfpunkt.
 */
public class Jobs implements AutoCloseable {

    static final String INTERRUPTED = "INTERRUPTED";
    static final String TIMEOUT = "TIMEOUT";
    static final String UNEXPECTED = "UNEXPECTED";
    static final String SHUTDOWN = "SHUTDOWN";

    private static final System.Logger LOG = System.getLogger(Jobs.class.getName());
    private static final String COMPONENT = "Jobs";
    /** So lange darf ein laufender Auftrag nach angefordertem Abbruch oder Anhalten bis zu seinem Prüfpunkt brauchen. */
    public static final Duration STOP_GRACE = Duration.ofSeconds(30);

    private final JobStore store;
    private final JobEventStore events;
    private final RouteLookup routes;
    private final Executor executor;
    private final JobLimits limits;
    private final Clock clock;

    private final Object lock = new Object();
    private final Deque<Job> queued = new ArrayDeque<>();
    private final Map<String, Job> open = new HashMap<>();
    /**
     * Angehaltene, gescheiterte und abgebrochene Aufträge einer Art mit {@link JobType#resumable()}, die sich im selben
     * Eintrag fortsetzen lassen; nur bis zum Ende dieses Dienstes, denn die Arbeit selbst lässt sich nicht ablegen.
     */
    private final Map<String, Job> stopped = new HashMap<>();
    private int running;
    /** Laufende Folgeaufträge ({@link JobType#followUp()}). */
    private int runningFollowUps;
    private boolean closed;

    /**
     * @param executor führt {@link JobTask#start} aus; die Parallelität begrenzt {@code Jobs} selbst, der Executor muss
     *                 also mindestens {@link JobLimits#maxConcurrent()} Aufgaben gleichzeitig annehmen
     */
    public Jobs(JobStore store, Executor executor, JobLimits limits, Clock clock) {
        this(store, new InMemoryJobEventStore(), RouteLookup.NONE, executor, limits, clock);
    }

    /**
     * @param events Ablage der Diagnoseereignisse
     * @param routes Proxy-Route je Quelle für Ereignisse gescheiterter Aufträge
     */
    public Jobs(JobStore store, JobEventStore events, RouteLookup routes, Executor executor, JobLimits limits,
                Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        markInterrupted();
        purgeExpired();
    }

    /**
     * Nimmt einen Auftrag an, ohne auf ihn zu warten.
     *
     * @param partitionKey Partition (Space) oder {@code null}
     * @param owner        auslösender Client oder {@code null} für interne Aufträge; siehe {@link JobStatus#owner()}
     * @return der Auftrag im Zustand {@link JobState#QUEUED}
     * @throws JobCapacityExceededException wenn bereits {@code maxConcurrent + maxQueued} Aufträge offen sind
     * @throws IllegalStateException         nach {@link #close()}
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String owner, JobTask task) {
        return submit(type, sourceId, partitionKey, null, owner, task);
    }

    /**
     * Wie {@link #submit(JobType, String, String, String, JobTask)}, für einen Auftrag, der nur eine Seite mit ihren
     * Unterseiten betrifft; der Zustand nennt sie ({@link JobStatus#rootPageId()}).
     *
     * @param rootPageId Startseite oder {@code null} für die ganze Partition
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
                            JobTask task) {
        return submit(type, sourceId, partitionKey, rootPageId, owner, Map.of(), task);
    }

    /**
     * Wie {@link #submit(JobType, String, String, String, String, JobTask)}, mit den Angaben, aus denen sich der
     * Auftrag nach einem Neustart gleichwertig neu anlegen lässt ({@link JobStatus#parameters()}).
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
                            Map<String, String> parameters, JobTask task) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(parameters, "parameters");
        purgeExpired();
        Job job = new Job(UUID.randomUUID().toString(), type, sourceId, partitionKey, rootPageId, owner,
                Map.copyOf(parameters), task, clock.instant());
        JobStatus status;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            requireCapacity(type, owner);
            // Vor dem Einreihen: sonst könnte ein paralleler dispatch() den Start vor der Annahme festhalten.
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag angenommen", null, null,
                    null, null);
            open.put(job.id, job);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /** Unter dem Sperrobjekt: ob noch ein Auftrag dieser Art für diesen Eigentümer Platz hat. */
    private void requireCapacity(JobType type, String owner) {
        int capacity = limits.maxConcurrent() + limits.maxQueued();
        // Folgeaufträge (Anreicherung, Bilder) entstehen, während ihr Auslöser noch einen Platz belegt. Sie haben
        // daher einen eigenen Vorrat derselben Größe und eine eigene Grenze je Eigentümer: zwei je erlaubtem
        // Auslöser, sonst nähme etwa maxOpenPerOwner=1 nie einen an, und ein Client belegt den Vorrat nicht allein.
        if (open.values().stream().filter(other -> other.type.followUp() == type.followUp()).count() >= capacity) {
            throw new JobCapacityExceededException(capacity);
        }
        int perOwner = type.followUp() ? 2 * limits.maxOpenPerOwner() : limits.maxOpenPerOwner();
        if (owner != null && open.values().stream()
                .filter(other -> other.type.followUp() == type.followUp() && owner.equals(other.owner))
                .count() >= perOwner) {
            throw JobCapacityExceededException.perOwner(perOwner);
        }
    }

    /** @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt */
    public JobStatus status(String jobId) {
        boolean detached = false;
        JobStatus status = null;
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null) {
                detached = detachIfStuck(job);
                status = job.snapshot();
            }
        }
        if (detached) {
            dispatch();
        }
        return status != null ? status : store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
    }

    /**
     * Unter dem Sperrobjekt: beendet einen laufenden Auftrag, der nach angefordertem Abbruch oder Anhalten länger als
     * {@link #STOP_GRACE} keinen Prüfpunkt erreicht hat, und koppelt seine Arbeit ab.
     *
     * @return ob er beendet wurde
     */
    private boolean detachIfStuck(Job job) {
        Instant requested = job.stopRequestedAt;
        if (job.state != JobState.RUNNING || !job.cancelRequested || requested == null
                || clock.instant().isBefore(requested.plus(STOP_GRACE))) {
            return false;
        }
        detach(job, job.pauseRequested ? JobState.PAUSED : JobState.CANCELLED,
                "der laufende Schritt antwortete nicht und wurde abgekoppelt");
        return true;
    }

    /**
     * Unter dem Sperrobjekt: beendet einen laufenden Auftrag sofort mit {@code end}, ohne auf seine Arbeit zu warten,
     * und gibt seinen Platz frei. Die Arbeit läuft womöglich weiter, bis sie einen Prüfpunkt erreicht; ihr Eintrag
     * bleibt davon unberührt ({@link Job#detached}). Fortsetzen startet einen neuen Lauf desselben Eintrags.
     */
    private void detach(Job job, JobState end, String why) {
        job.finish(end, null, clock.instant());
        running--;
        if (job.type.followUp()) {
            runningFollowUps--;
        }
        open.remove(job.id);
        lock.notifyAll();
        JobStatus finished = job.snapshot();
        job.emit(true, JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, end.name(),
                (end == JobState.PAUSED ? "Auftrag angehalten: " : "Auftrag abgebrochen: ") + why, null, null, null,
                null);
        job.detached = true;
        store.save(finished);
    }

    /**
     * Wie {@link #status(String)}, aber nur für den eigenen Auftrag: ein Auftrag eines anderen Clients gilt als
     * unbekannt, damit fremde Job-IDs nicht einmal ihre Existenz verraten.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public JobStatus status(String jobId, String owner) {
        return requireOwner(status(jobId), owner);
    }

    /**
     * Die eigenen Aufträge einer Art mit laufenden Zählern, neueste zuerst; so findet ein Client auch Aufträge, die
     * nicht er selbst eingeplant hat, etwa die Anreicherung nach seinem Abgleich ({@link JobType#ENRICHMENT}).
     */
    public List<JobStatus> list(String owner, JobType type) {
        List<JobStatus> result = new ArrayList<>();
        for (JobStatus stored : store.all()) {
            if (Objects.equals(stored.owner(), owner) && stored.type() == type) {
                try {
                    result.add(status(stored.id()));
                } catch (UnknownJobException e) {
                    // inzwischen gelöscht
                }
            }
        }
        result.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        return result;
    }

    /**
     * Wie {@link #cancel(String)}, aber nur für den eigenen Auftrag.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public JobStatus cancel(String jobId, String owner) {
        requireOwner(status(jobId), owner);
        return cancel(jobId);
    }

    /**
     * Hält einen eigenen Auftrag an (ADR 0025). Ein wartender ist danach {@link JobState#PAUSED}, ein laufender beendet
     * seinen Schritt am nächsten {@link JobContext#checkpoint()} und ist dann angehalten; was er bis dahin geladen
     * hat, bleibt. Für einen beendeten Auftrag ändert sich nichts.
     *
     * @throws UnknownJobException       wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException wenn sich Aufträge dieser Art nicht fortsetzen lassen
     */
    public JobStatus pause(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht anhalten");
        }
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null) {
                if (job.cancelRequested && !job.pauseRequested) {
                    // Ein angeforderter Abbruch bleibt ein Abbruch.
                    return job.snapshot();
                }
                job.pauseRequested = true;
                job.pauseReason = PauseReason.USER;
                job.cancelRequested = true;
                if (job.stopRequestedAt == null) {
                    job.stopRequestedAt = clock.instant();
                }
                boolean wasQueued = job.state == JobState.QUEUED;
                if (wasQueued) {
                    queued.remove(job);
                    open.remove(jobId);
                    lock.notifyAll();
                    job.finish(JobState.PAUSED, null, clock.instant());
                    stopped.put(jobId, job);
                }
                job.emit(wasQueued, JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT,
                        wasQueued ? "PAUSED" : null, wasQueued ? "Auftrag vor dem Start angehalten"
                                : "Anhalten angefordert", null, null, null, null);
                JobStatus status = job.snapshot();
                store.save(status);
                return status;
            }
        }
        return current;
    }

    /**
     * Zieht einen eigenen wartenden Auftrag an den Anfang der Warteschlange („Jetzt starten“); er startet, sobald ein
     * Platz frei ist, die Grenze gleichzeitiger Aufträge gilt weiter. Abgleiche und Exporte starten weiterhin vor
     * Folgeaufträgen. Für einen laufenden oder beendeten Auftrag ändert sich nichts.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public JobStatus prioritize(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job == null || job.state != JobState.QUEUED || !queued.remove(job)) {
                return current;
            }
            queued.addFirst(job);
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag vorgezogen", null,
                    null, null, null);
        }
        dispatch();
        return status(jobId);
    }

    /**
     * Setzt einen eigenen angehaltenen, gescheiterten oder abgebrochenen Auftrag im selben Eintrag fort (ADR 0025): er
     * wartet wieder auf einen Platz und läuft dann von vorn, holt aber nur, was noch fehlt, weil bereits geladene
     * Seiten liegen bleiben. Die Zähler beginnen dabei neu, damit keine Seite doppelt zählt.
     *
     * @throws UnknownJobException          wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException    wenn die Art kein Fortsetzen kennt, der Auftrag noch läuft oder fertig ist,
     *                                      oder er aus der Zeit vor dem letzten Neustart des Dienstes stammt
     * @throws JobCapacityExceededException wenn zu viele Aufträge offen sind
     */
    public JobStatus resume(String jobId, String owner) {
        return resume(jobId, owner, null);
    }

    /**
     * Wie {@link #resume(String, String)}; {@code renew} ersetzt vorher die Arbeit des Auftrags, etwa um die
     * Berechtigungsprüfung des Auftraggebers mit seinem aktuellen Zugang statt dem vom Start zu führen.
     *
     * @param renew bekommt die bisherige Arbeit und liefert die neue, oder {@code null} für unverändert
     */
    public JobStatus resume(String jobId, String owner, UnaryOperator<JobTask> renew) {
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht fortsetzen");
        }
        if (!resumableState(current.state())) {
            throw new JobNotResumableException(jobId, current.state().terminal() ? "der Auftrag ist fertig"
                    : "der Auftrag wartet oder läuft noch");
        }
        JobStatus status;
        synchronized (lock) {
            Job job = stopped.get(jobId);
            if (job == null) {
                throw new JobNotResumableException(jobId, "der Auftrag stammt aus der Zeit vor dem letzten Neustart; "
                        + "ein neuer Start holt ebenfalls nur, was noch fehlt");
            }
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            requireCapacity(job.type, job.owner);
            JobTask task = renew == null ? job.task : Objects.requireNonNull(renew.apply(job.task), "task");
            stopped.remove(jobId);
            job.task = task;
            job.reopen();
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag fortgesetzt", null,
                    null, null, null);
            open.put(job.id, job);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /**
     * Lässt einen eigenen beendeten Auftrag einer fortsetzbaren Art im selben Eintrag mit neuer Arbeit wieder laufen
     * („Fortsetzen“ nach einem Neustart, „Nur Fehler neu versuchen“, „Neu erzwingen“): kein neuer Auftrag, dieselbe
     * ID, dieselben Angaben ({@link JobStatus#parameters()}), die Diagnose läuft weiter. Anders als
     * {@link #resume(String, String, UnaryOperator)} geht das auch für fertige Aufträge und für Aufträge aus der Zeit
     * vor dem letzten Neustart; die Arbeit liefert der Aufrufer, meist aus den abgelegten Angaben. Die Zähler beginnen
     * neu.
     *
     * @param task die neue Arbeit des Auftrags
     * @throws UnknownJobException          wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobNotResumableException    wenn die Art kein Fortsetzen kennt oder der Auftrag noch wartet oder läuft
     * @throws JobCapacityExceededException wenn zu viele Aufträge offen sind
     */
    public JobStatus rerun(String jobId, String owner, JobTask task) {
        return rerun(jobId, owner, null, task);
    }

    /**
     * Wie {@link #rerun(String, String, JobTask)}; {@code parameters} ersetzt die abgelegten Angaben, damit ein
     * Fortsetzen nach einem Neustart dieselbe Arbeit wiederherstellt, etwa „Neu erzwingen“ statt eines gewöhnlichen
     * Laufs.
     *
     * @param parameters die neuen Angaben, oder {@code null} für unverändert
     */
    public JobStatus rerun(String jobId, String owner, Map<String, String> parameters, JobTask task) {
        Objects.requireNonNull(task, "task");
        Map<String, String> renewed = parameters == null ? null : Map.copyOf(parameters);
        JobStatus current = requireOwner(status(jobId), owner);
        if (!current.type().resumable()) {
            throw new JobNotResumableException(jobId, "diese Auftragsart lässt sich nicht erneut starten");
        }
        JobStatus status;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (open.containsKey(jobId)) {
                throw new JobNotResumableException(jobId, "der Auftrag wartet oder läuft noch");
            }
            Job job = stopped.get(jobId);
            if (job == null) {
                // Unter dem Sperrobjekt erneut gelesen: ein paralleles Löschen gewinnt.
                JobStatus stored = store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
                if (!stored.state().terminal()) {
                    throw new JobNotResumableException(jobId, "der Auftrag wartet oder läuft noch");
                }
                job = new Job(stored.id(), stored.type(), stored.sourceId(), stored.partitionKey(),
                        stored.rootPageId(), stored.owner(), stored.parameters(), task, stored.createdAt());
                job.rootTitle = stored.rootTitle();
                job.sequence.set(events.events(jobId).stream().mapToLong(JobEvent::sequence).max().orElse(0));
            }
            requireCapacity(job.type, job.owner);
            stopped.remove(jobId);
            job.task = task;
            if (renewed != null) {
                job.parameters = renewed;
            }
            job.reopen();
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag erneut gestartet",
                    null, null, null, null);
            open.put(job.id, job);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        dispatch();
        return status;
    }

    /** Ob ein Auftrag in diesem Zustand im selben Eintrag fortgesetzt werden kann, sofern seine Art es erlaubt. */
    public static boolean resumableState(JobState state) {
        return state == JobState.PAUSED || state == JobState.FAILED || state == JobState.CANCELLED;
    }

    /**
     * Ob sich dieser eigene Auftrag jetzt im selben Eintrag fortsetzen lässt ({@link #resume(String, String)}); sonst
     * startet ein Client ihn mit denselben Angaben neu.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public boolean resumable(String jobId, String owner) {
        JobStatus current = requireOwner(status(jobId), owner);
        synchronized (lock) {
            return current.type().resumable() && resumableState(current.state()) && stopped.containsKey(jobId);
        }
    }

    /**
     * Diagnoseereignisse eines Auftrags in ihrer Reihenfolge.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt
     */
    public List<JobEvent> events(String jobId) {
        status(jobId);
        return events.events(jobId);
    }

    /**
     * Wie {@link #events(String)}, aber nur für den eigenen Auftrag.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public List<JobEvent> events(String jobId, String owner) {
        requireOwner(status(jobId), owner);
        return events.events(jobId);
    }

    /** Die jüngsten Diagnoseereignisse der Aufträge dieses Clients, neueste zuerst. */
    public List<JobEvent> recentEvents(String owner, int limit) {
        if (limit < 1) {
            return List.of();
        }
        // Je eigenem Auftrag lesen statt global und dann filtern: fremde Ereignisse dürfen eigene nicht verdrängen.
        Set<String> mine = new LinkedHashSet<>();
        synchronized (lock) {
            open.values().stream().filter(job -> Objects.equals(job.owner, owner)).forEach(job -> mine.add(job.id));
        }
        store.all().stream().filter(status -> Objects.equals(status.owner(), owner))
                .forEach(status -> mine.add(status.id()));
        return mine.stream().flatMap(id -> events.events(id).stream())
                .sorted(Comparator.comparing(JobEvent::at).thenComparingLong(JobEvent::sequence).reversed())
                .limit(limit).toList();
    }

    /**
     * Löscht einen eigenen Auftrag in jedem Zustand samt seinen Diagnoseereignissen (ADR 0025). Ein wartender wird
     * vorher abgebrochen, ein laufender sofort beendet und seine Arbeit abgekoppelt: sie endet an ihrem nächsten
     * Prüfpunkt und hinterlässt nichts mehr im Eintrag. Ein Exportartefakt im Exportverzeichnis gehört zum Auftrag und
     * wird mit ihm unerreichbar; das nächste {@code Exports.purge()} räumt es weg. Kopien, die der Benutzer anderswo
     * gespeichert hat, bleiben unberührt.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     */
    public void delete(String jobId, String owner) {
        boolean freed;
        // Unter dem Sperrobjekt, damit kein gleichzeitiges Fortsetzen einen gerade gelöschten Auftrag wieder öffnet.
        synchronized (lock) {
            Job open = this.open.get(jobId);
            JobStatus status = requireOwner(open != null ? open.snapshot()
                    : store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId)), owner);
            freed = stop(jobId);
            stopped.remove(status.id());
            // Erst die Ereignisse: scheitert das, bleibt der Auftrag und der Fehler kommt beim Aufrufer an.
            events.delete(status.id());
            store.delete(status.id());
        }
        if (freed) {
            dispatch();
        }
    }

    /**
     * Unter dem Sperrobjekt: beendet einen offenen Auftrag fürs Löschen, ohne Ereignis. Ein wartender verlässt die
     * Warteschlange, ein laufender wird abgekoppelt.
     *
     * @return ob ein Platz frei wurde
     */
    private boolean stop(String jobId) {
        Job job = open.get(jobId);
        if (job == null) {
            return false;
        }
        job.cancelRequested = true;
        job.pauseRequested = false;
        job.detached = true;
        open.remove(jobId);
        lock.notifyAll();
        if (job.state == JobState.QUEUED) {
            queued.remove(job);
            job.finish(JobState.CANCELLED, null, clock.instant());
            return false;
        }
        job.finish(JobState.CANCELLED, null, clock.instant());
        running--;
        if (job.type.followUp()) {
            runningFollowUps--;
        }
        return true;
    }

    /**
     * Löscht alle eigenen Aufträge in jedem Zustand samt ihren Ereignissen, wie {@link #delete(String, String)}.
     *
     * @param type nur Aufträge dieser Art oder {@code null} für alle
     * @return Anzahl gelöschter Aufträge
     */
    public int deleteAll(String owner, JobType type) {
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (Objects.equals(status.owner(), owner) && (type == null || status.type() == type)) {
                try {
                    delete(status.id(), owner);
                    removed++;
                } catch (UnknownJobException e) {
                    // inzwischen gelöscht
                }
            }
        }
        return removed;
    }

    /**
     * Löscht alle eigenen beendeten Aufträge (abgeschlossen, gescheitert, abgebrochen) samt ihren Ereignissen;
     * wartende und laufende bleiben.
     *
     * @param type nur Aufträge dieser Art oder {@code null} für alle
     * @return Anzahl gelöschter Aufträge
     */
    public int deleteFinished(String owner, JobType type) {
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (Objects.equals(status.owner(), owner) && (type == null || status.type() == type)
                    && deleteIfFinished(status.id(), false)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Löscht ein Diagnoseereignis eines eigenen beendeten Auftrags. Die Nummern der übrigen Ereignisse bleiben, und
     * keine Nummer wird je wieder vergeben.
     *
     * @return {@code false}, wenn der Auftrag kein Ereignis mit dieser Nummer hat
     * @throws UnknownJobException      wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobStillRunningException wenn der Auftrag noch wartet oder läuft
     */
    public boolean deleteEvent(String jobId, long sequence, String owner) {
        requireFinished(requireOwner(status(jobId), owner));
        boolean present = events.events(jobId).stream().anyMatch(event -> event.sequence() == sequence);
        if (present) {
            events.delete(jobId, List.of(sequence));
        }
        return present;
    }

    /**
     * Löscht die Diagnoseereignisse eines eigenen beendeten Auftrags, die vor {@code before} entstanden sind.
     *
     * @return Anzahl gelöschter Ereignisse
     * @throws UnknownJobException      wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobStillRunningException wenn der Auftrag noch wartet oder läuft
     */
    public int deleteEventsBefore(String jobId, Instant before, String owner) {
        Objects.requireNonNull(before, "before");
        requireFinished(requireOwner(status(jobId), owner));
        return deleteBefore(jobId, before);
    }

    /**
     * Löscht die Diagnoseereignisse aller eigenen beendeten Aufträge, die vor {@code before} entstanden sind; die
     * Aufträge selbst bleiben. Ereignisse wartender und laufender Aufträge bleiben.
     *
     * @return Anzahl gelöschter Ereignisse
     */
    public int deleteEventsBefore(Instant before, String owner) {
        Objects.requireNonNull(before, "before");
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (Objects.equals(status.owner(), owner) && status.state().terminal()) {
                removed += deleteBefore(status.id(), before);
            }
        }
        return removed;
    }

    private int deleteBefore(String jobId, Instant before) {
        List<Long> old = events.events(jobId).stream().filter(event -> event.at().isBefore(before))
                .map(JobEvent::sequence).toList();
        if (!old.isEmpty()) {
            events.delete(jobId, old);
        }
        return old.size();
    }

    private static JobStatus requireFinished(JobStatus status) {
        if (!status.state().terminal()) {
            throw new JobStillRunningException(status.id());
        }
        return status;
    }

    private static JobStatus requireOwner(JobStatus status, String owner) {
        if (!Objects.equals(status.owner(), owner)) {
            throw new UnknownJobException(status.id());
        }
        return status;
    }

    /**
     * Bricht alle offenen Aufträge einer Quelle ab, etwa wenn sie entfernt wird (#188), und wartet, bis keiner mehr
     * läuft. Laufende Aufträge enden an ihrem nächsten {@link JobContext#checkpoint()}.
     *
     * @return {@code true}, wenn innerhalb von {@code timeout} keiner der Quelle mehr offen ist
     */
    /**
     * Quelle eines Auftrags über alle Quellen, etwa des Knowledge-Graph-Exports; {@link #cancelSource} beendet ihn
     * für jede Quelle mit.
     */
    public static final String ALL_SOURCES = "*";

    public boolean cancelSource(String sourceId, Duration timeout) throws InterruptedException {
        return cancelSource(sourceId, null, timeout);
    }

    /**
     * Wie {@link #cancelSource(String, Duration)}, lässt aber den Auftrag {@code exceptJobId} laufen, etwa den
     * Bereinigungsauftrag, der die übrigen Aufträge seiner Quelle beendet.
     *
     * @param exceptJobId Auftrag, der weiterläuft, oder {@code null}
     */
    public boolean cancelSource(String sourceId, String exceptJobId, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(timeout, "timeout");
        // Quellenübergreifende Aufträge (ALL_SOURCES) können Daten jeder Quelle enthalten: sie enden mit.
        java.util.function.Predicate<Job> affected = job -> (job.sourceId.equals(sourceId)
                || ALL_SOURCES.equals(job.sourceId)) && !job.id.equals(exceptJobId);
        List<String> ids;
        synchronized (lock) {
            ids = open.values().stream().filter(affected).map(job -> job.id).toList();
            // Ohne Quelle gibt es nichts fortzusetzen.
            stopped.values().removeIf(affected);
        }
        for (String id : ids) {
            try {
                cancel(id);
            } catch (UnknownJobException e) {
                // inzwischen beendet und gelöscht
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (open.values().stream().anyMatch(affected)) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return false;
                }
                lock.wait(left);
            }
        }
        return true;
    }

    /**
     * Fordert den Abbruch an. Ein wartender Auftrag ist danach {@link JobState#CANCELLED}, ein laufender trägt
     * {@link JobStatus#cancelRequested()} bis zu seinem nächsten {@link JobContext#checkpoint()}. Für einen beendeten
     * Auftrag ändert sich nichts.
     *
     * @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt
     */
    public JobStatus cancel(String jobId) {
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null) {
                job.cancelRequested = true;
                if (job.stopRequestedAt == null) {
                    job.stopRequestedAt = clock.instant();
                }
                // Abbrechen geht vor Anhalten.
                job.pauseRequested = false;
                boolean wasQueued = job.state == JobState.QUEUED;
                if (wasQueued) {
                    queued.remove(job);
                    open.remove(jobId);
                    lock.notifyAll();
                    job.finish(JobState.CANCELLED, null, clock.instant());
                    keepResumable(job);
                }
                // Erst das Ereignis, dann der Zustand: Ein beendeter Auftrag bekommt danach nie mehr ein Ereignis, sonst
                // könnte eine Löschung dazwischen eines verwaist zurücklassen.
                job.emit(wasQueued, wasQueued ? JobEvent.Severity.WARNING : JobEvent.Severity.INFO,
                        JobEvent.Category.JOB, COMPONENT, wasQueued ? "CANCELLED" : null,
                        wasQueued ? "Auftrag vor dem Start abgebrochen" : "Abbruch angefordert", null, null, null,
                        null);
                JobStatus status = job.snapshot();
                store.save(status);
                return status;
            }
        }
        return store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
    }

    /**
     * Nimmt keine Aufträge mehr an, bricht wartende ab und fordert den Abbruch der laufenden an. Läuft ein Auftrag über
     * das Ende des Dienstes hinaus, gilt er beim nächsten Start als {@code INTERRUPTED}.
     */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            Instant now = clock.instant();
            for (Job job : queued) {
                open.remove(job.id);
                job.cancelRequested = true;
                job.finish(JobState.CANCELLED, null, now);
                store.save(job.snapshot());
            }
            queued.clear();
            for (Job job : open.values()) {
                job.cancelRequested = true;
                store.save(job.snapshot());
            }
        }
    }

    /**
     * Entfernt beendete Aufträge, deren Ende länger als {@link JobLimits#retention()} zurückliegt.
     *
     * @return Anzahl entfernter Aufträge
     */
    public int purgeExpired() {
        Instant limit = clock.instant().minus(limits.retention());
        int removed = 0;
        for (JobStatus status : store.all()) {
            if (status.state().terminal() && status.finishedAt() != null && status.finishedAt().isBefore(limit)
                    && deleteIfFinished(status.id(), true)) {
                removed++;
            }
        }
        return removed;
    }

    private void markInterrupted() {
        Instant now = clock.instant();
        for (JobStatus status : store.all()) {
            if (!status.state().terminal()) {
                store.save(status.interrupted(new JobError(INTERRUPTED, "Der Dienst wurde während des Auftrags "
                        + "beendet; der Auftrag wird nicht fortgesetzt und muss neu gestartet werden"), now));
                // Nach der höchsten Nummer, nicht nach der Anzahl: gelöschte Ereignisse hinterlassen Lücken.
                long next = events.events(status.id()).stream().mapToLong(JobEvent::sequence).max().orElse(0) + 1;
                appendQuietly(new JobEvent(status.id(), next, now, status.type(),
                        status.sourceId(), status.partitionKey(), JobEvent.Severity.ERROR, JobEvent.Category.JOB,
                        COMPONENT, null, INTERRUPTED, "Der Dienst wurde während des Auftrags beendet", null, null,
                        null, null));
            }
        }
    }

    /**
     * Löscht einen beendeten Auftrag samt Ereignissen, geprüft und gelöscht unter dem Sperrobjekt, damit ein
     * gleichzeitiges {@link #resume} ihn nicht dazwischen wieder öffnet.
     *
     * @param quietly Fehler beim Löschen der Ereignisse nur protokollieren; der Auftrag bleibt dann stehen
     * @return ob gelöscht wurde
     */
    private boolean deleteIfFinished(String jobId, boolean quietly) {
        synchronized (lock) {
            if (open.containsKey(jobId)) {
                return false;
            }
            Optional<JobStatus> stored = store.find(jobId);
            if (stored.isEmpty() || !stored.get().state().terminal()) {
                return false;
            }
            // Erst die Ereignisse: scheitert das, bleibt der Auftrag und der nächste Lauf versucht es erneut.
            if (quietly) {
                if (!deleteEventsQuietly(jobId)) {
                    return false;
                }
            } else {
                events.delete(jobId);
            }
            stopped.remove(jobId);
            store.delete(jobId);
            return true;
        }
    }

    private void appendQuietly(JobEvent event) {
        try {
            events.append(event);
        } catch (RuntimeException e) {
            // Diagnose darf keinen Auftrag scheitern lassen.
            LOG.log(System.Logger.Level.WARNING, "Diagnoseereignis nicht abgelegt ({0})", e.getClass().getName());
        }
    }

    private boolean deleteEventsQuietly(String jobId) {
        try {
            events.delete(jobId);
            return true;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Diagnoseereignisse nicht entfernt ({0})", e.getClass().getName());
            return false;
        }
    }

    private void dispatch() {
        List<Job> toStart = new ArrayList<>();
        synchronized (lock) {
            while (running < limits.maxConcurrent() && !queued.isEmpty()) {
                Job job = next();
                if (job == null) {
                    break;
                }
                running++;
                if (job.type.followUp()) {
                    runningFollowUps++;
                }
                job.state = JobState.RUNNING;
                job.startedAt = clock.instant();
                job.deadline = job.startedAt.plus(limits.maxRunTime());
                store.save(job.snapshot());
                toStart.add(job);
            }
        }
        for (Job job : toStart) {
            job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag gestartet", null, null,
                    null, null);
            try {
                executor.execute(() -> run(job));
            } catch (RejectedExecutionException e) {
                complete(job, new JobFailedException(SHUTDOWN, "Der Auftrag konnte nicht gestartet werden, "
                        + "weil der Dienst beendet wird"));
            }
        }
    }

    /**
     * Der nächste startbare Auftrag: Abgleiche und Exporte vor Folgeaufträgen, damit Anreicherung und Bilder nie einen
     * Export aufhalten; Folgeaufträge belegen höchstens alle Plätze bis auf einen. Unter dem Sperrobjekt.
     */
    private Job next() {
        for (java.util.Iterator<Job> it = queued.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (!job.type.followUp()) {
                it.remove();
                return job;
            }
        }
        if (runningFollowUps >= Math.max(1, limits.maxConcurrent() - 1)) {
            return null;
        }
        return queued.pollFirst();
    }

    private void run(Job job) {
        CompletionStage<Void> stage;
        try {
            stage = job.task.start(job);
            if (stage == null) {
                stage = CompletableFuture.failedFuture(new IllegalStateException("JobTask lieferte null"));
            }
        } catch (Throwable e) {
            stage = CompletableFuture.failedFuture(e);
        }
        stage.whenComplete((ignored, failure) -> complete(job, failure));
    }

    private void complete(Job job, Throwable failure) {
        Throwable cause = unwrap(failure);
        JobStatus finished;
        synchronized (lock) {
            if (job.state.terminal()) {
                return;
            }
            Instant now = clock.instant();
            if (cause == null) {
                job.finish(JobState.COMPLETED, null, now);
            } else if (cause instanceof JobCancelledException) {
                job.finish(job.pauseRequested ? JobState.PAUSED : JobState.CANCELLED, null, now);
            } else {
                job.finish(JobState.FAILED, errorOf(cause), now);
            }
            keepResumable(job);
            running--;
            if (job.type.followUp()) {
                runningFollowUps--;
            }
            open.remove(job.id);
            lock.notifyAll();
            finished = job.snapshot();
            // Erst das Endereignis, dann der Endzustand: Wer den Endzustand sieht, findet auch die Diagnose.
            recordEnd(job, finished, cause);
            store.save(finished);
        }
        dispatch();
    }

    /** Unter dem Sperrobjekt: hält einen beendeten Auftrag zum Fortsetzen bereit, wenn Art und Zustand es erlauben. */
    private void keepResumable(Job job) {
        if (!job.detached && job.type.resumable() && resumableState(job.state)) {
            stopped.put(job.id, job);
        }
    }

    private void recordEnd(Job job, JobStatus finished, Throwable cause) {
        JobProgress progress = finished.progress();
        switch (finished.state()) {
            case COMPLETED -> job.emit(true, JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                    "Auftrag abgeschlossen: " + progress.processed() + " Seiten verarbeitet, " + progress.written()
                            + " geschrieben, " + progress.failed() + " gescheitert", null, null, null, null);
            case CANCELLED -> job.emit(true, JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, "CANCELLED",
                    "Auftrag abgebrochen", null, null, null, null);
            case PAUSED -> {
                PauseReason reason = finished.pauseReason();
                String why = switch (reason) {
                    case SIGN_IN_REQUIRED -> "Auftrag angehalten, weil die Anmeldung fehlt: ";
                    case CONNECTION_LOST -> "Auftrag angehalten, weil die Quelle nicht erreichbar ist: ";
                    case USER -> "Auftrag angehalten: ";
                };
                job.emit(true, reason == PauseReason.USER ? JobEvent.Severity.INFO : JobEvent.Severity.WARNING,
                        JobEvent.Category.JOB, COMPONENT, reason == PauseReason.USER ? "PAUSED" : reason.name(),
                        why + progress.processed() + " Seiten verarbeitet; Fortsetzen holt nur, was noch fehlt", null,
                        null, null, null);
            }
            default -> {
                JobError error = finished.error();
                FailureDiagnosis.Findings findings = FailureDiagnosis.of(cause, error.code());
                String route = findings.route();
                if (route == null && (findings.category() == JobEvent.Category.NETWORK
                        || findings.category() == JobEvent.Category.SOURCE)) {
                    try {
                        route = routes.routeOf(job.sourceId).orElse(null);
                    } catch (RuntimeException e) {
                        route = "nicht bestimmbar (" + e.getClass().getSimpleName() + ")";
                    }
                }
                // Gewollte Abbrüche ohne Ursache brauchen keinen Stacktrace; alles andere bekommt die ganze Kette.
                boolean plain = cause instanceof JobFailedException && cause.getCause() == null;
                job.emit(true, JobEvent.Severity.ERROR, findings.category(),
                        findings.component() == null ? job.component : findings.component(), error.code(),
                        error.message(), findings.httpStatus(), route, findings.tlsFinding(),
                        plain ? null : findings.detail());
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Nachrichten unbekannter Ausnahmen können URLs oder Zugangsdaten enthalten und erscheinen deshalb nie. */
    private static JobError errorOf(Throwable cause) {
        if (cause instanceof JobFailedException failed) {
            return new JobError(failed.code(), failed.getMessage());
        }
        if (cause instanceof SourceAccessException source) {
            // Fester Text: Meldungen des Transports können interne URLs, Hosts und Proxy-Routen enthalten.
            return new JobError("SOURCE_" + source.reason().name(),
                    "Die Quelle ist nicht erreichbar oder hat den Abruf abgelehnt (" + source.reason().name() + ")");
        }
        return new JobError(UNEXPECTED, "Unerwarteter Fehler im Auftrag (" + cause.getClass().getSimpleName() + ")");
    }

    /** Veränderlicher Zustand eines offenen Auftrags; Felder außer den Zählern nur unter {@code lock} ändern. */
    private final class Job implements JobContext {

        final String id;
        final JobType type;
        final String sourceId;
        final String partitionKey;
        final String rootPageId;
        final String owner;
        /** Nur unter dem Sperrobjekt neu gesetzt, bei {@link #rerun(String, String, Map, JobTask)}. */
        volatile Map<String, String> parameters;
        /** Ergebnis unvollständig ({@link JobContext#incomplete()}). */
        volatile boolean incomplete;
        /** Nur unter dem Sperrobjekt neu gesetzt, beim Fortsetzen ({@link #resume(String, String, UnaryOperator)}). */
        volatile JobTask task;
        final Instant createdAt;
        final AtomicLong discovered = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong written = new AtomicLong();
        final AtomicLong failedPages = new AtomicLong();
        volatile JobState state = JobState.QUEUED;
        volatile boolean cancelRequested;
        /** Mit {@link #cancelRequested}: der Benutzer hält an, statt abzubrechen. */
        volatile boolean pauseRequested;
        /** Wann Abbruch oder Anhalten zuerst angefordert wurde, für {@link #STOP_GRACE}. */
        volatile Instant stopRequestedAt;
        /**
         * Die Arbeit ist abgekoppelt: der Eintrag ist beendet oder gelöscht, ihre Ereignisse und ihr Ende zählen nicht
         * mehr. Wird nie zurückgesetzt; Fortsetzen legt dafür einen neuen Lauf an.
         */
        volatile boolean detached;
        /** Warum angehalten wird; {@code null} heißt durch den Benutzer. */
        volatile PauseReason pauseReason;
        volatile Instant startedAt;
        volatile Instant deadline;
        Instant finishedAt;
        JobError error;
        final AtomicLong sequence = new AtomicLong();
        volatile String component = COMPONENT;
        volatile String phase;
        volatile String rootTitle;

        Job(String id, JobType type, String sourceId, String partitionKey, String rootPageId, String owner,
            Map<String, String> parameters, JobTask task, Instant createdAt) {
            this.parameters = parameters;
            this.id = id;
            this.type = type;
            this.sourceId = sourceId;
            this.partitionKey = partitionKey;
            this.rootPageId = rootPageId;
            this.owner = owner;
            this.task = task;
            this.createdAt = createdAt;
        }

        void finish(JobState end, JobError failure, Instant at) {
            state = end;
            error = failure;
            finishedAt = at;
        }

        /** Unter dem Sperrobjekt: wieder wartend wie neu angenommen, mit leeren Zählern (ADR 0025). */
        void reopen() {
            state = JobState.QUEUED;
            error = null;
            finishedAt = null;
            startedAt = null;
            deadline = null;
            cancelRequested = false;
            pauseRequested = false;
            stopRequestedAt = null;
            pauseReason = null;
            incomplete = false;
            discovered.set(0);
            processed.set(0);
            written.set(0);
            failedPages.set(0);
        }

        JobStatus snapshot() {
            return new JobStatus(id, type, sourceId, partitionKey, owner, state,
                    new JobProgress(discovered.get(), processed.get(), written.get(), failedPages.get()), error,
                    cancelRequested && !state.terminal(), createdAt, startedAt, finishedAt, rootPageId,
                    rootPageId == null ? null : rootTitle, parameters, incomplete && state == JobState.COMPLETED,
                    pauseRequested && cancelRequested && !state.terminal(), pauseReason);
        }

        @Override
        public void pause(PauseReason reason) {
            synchronized (lock) {
                // Ein angeforderter Abbruch bleibt ein Abbruch.
                if (!cancelRequested || pauseRequested) {
                    pauseReason = reason == null ? PauseReason.USER : reason;
                    pauseRequested = true;
                    cancelRequested = true;
                }
            }
            throw new JobCancelledException(id);
        }

        @Override
        public void incomplete() {
            this.incomplete = true;
        }

        @Override
        public void rootTitle(String title) {
            this.rootTitle = title;
        }

        @Override
        public String jobId() {
            return id;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public void checkpoint() {
            if (cancelRequested) {
                throw new JobCancelledException(id);
            }
            Instant limit = deadline;
            if (limit != null && clock.instant().isAfter(limit)) {
                throw new JobFailedException(TIMEOUT, "Laufzeitgrenze von " + limits.maxRunTime()
                        + " überschritten");
            }
        }

        @Override
        public boolean cancelRequested() {
            return cancelRequested;
        }

        @Override
        public void discovered(long pages) {
            discovered.addAndGet(pages);
        }

        @Override
        public void processed(long pages) {
            processed.addAndGet(pages);
        }

        @Override
        public void written(long pages) {
            written.addAndGet(pages);
        }

        @Override
        public void failed(long pages) {
            failedPages.addAndGet(pages);
        }

        @Override
        public void phase(String component, String phase) {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(phase, "phase");
            boolean changed = !phase.equals(this.phase) || !component.equals(this.component);
            this.component = component;
            this.phase = phase;
            if (changed) {
                emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, component, null, "Phase: " + phase, null, null,
                        null, null);
            }
        }

        @Override
        public void record(JobEvent.Draft event) {
            Objects.requireNonNull(event, "event");
            String detail = null;
            Integer httpStatus = event.httpStatus();
            String tls = event.tlsFinding();
            String route = null;
            if (event.cause() != null) {
                FailureDiagnosis.Findings findings = FailureDiagnosis.of(event.cause(), event.code());
                detail = findings.detail();
                httpStatus = httpStatus == null ? findings.httpStatus() : httpStatus;
                tls = tls == null ? findings.tlsFinding() : tls;
                route = findings.route();
            }
            emit(event.severity(), event.category(), event.component(), event.code(), event.message(), httpStatus,
                    route, tls, detail);
        }

        void emit(JobEvent.Severity severity, JobEvent.Category category, String eventComponent, String code,
                  String message, Integer httpStatus, String proxyRoute, String tlsFinding, String detail) {
            emit(false, severity, category, eventComponent, code, message, httpStatus, proxyRoute, tlsFinding,
                    detail);
        }

        /**
         * @param end das Endereignis: es bekommt immer den letzten freien Platz der Ablage, alle anderen nur bis
         *            {@link JobEventStore#MAX_EVENTS_PER_JOB} − 1
         */
        void emit(boolean end, JobEvent.Severity severity, JobEvent.Category category, String eventComponent,
                  String code, String message, Integer httpStatus, String proxyRoute, String tlsFinding,
                  String detail) {
            if (detached) {
                return;
            }
            long current;
            do {
                current = sequence.get();
                if (!end && current >= JobEventStore.MAX_EVENTS_PER_JOB - 1) {
                    return;
                }
            } while (!sequence.compareAndSet(current, current + 1));
            try {
                appendQuietly(new JobEvent(id, current + 1, clock.instant(), type, sourceId,
                        partitionKey, severity, category, eventComponent, phase, code, message, httpStatus,
                        proxyRoute, tlsFinding, detail));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Diagnoseereignis nicht erzeugt ({0})", e.getClass().getName());
            }
        }
    }
}

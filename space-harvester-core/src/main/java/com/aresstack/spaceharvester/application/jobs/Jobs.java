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
 * <p>Der Auftraggeber kann beendete Aufträge und ihre Ereignisse selbst löschen ({@link #delete(String, String)},
 * {@link #deleteFinished}, {@link #deleteEvent}, {@link #deleteEventsBefore(Instant, String)}); wartende und laufende
 * Aufträge nie ({@link JobStillRunningException}).
 */
public class Jobs implements AutoCloseable {

    static final String INTERRUPTED = "INTERRUPTED";
    static final String TIMEOUT = "TIMEOUT";
    static final String UNEXPECTED = "UNEXPECTED";
    static final String SHUTDOWN = "SHUTDOWN";

    private static final System.Logger LOG = System.getLogger(Jobs.class.getName());
    private static final String COMPONENT = "Jobs";

    private final JobStore store;
    private final JobEventStore events;
    private final RouteLookup routes;
    private final Executor executor;
    private final JobLimits limits;
    private final Clock clock;

    private final Object lock = new Object();
    private final Deque<Job> queued = new ArrayDeque<>();
    private final Map<String, Job> open = new HashMap<>();
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
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(task, "task");
        purgeExpired();
        Job job = new Job(UUID.randomUUID().toString(), type, sourceId, partitionKey, owner, task, clock.instant());
        JobStatus status;
        synchronized (lock) {
            int capacity = limits.maxConcurrent() + limits.maxQueued();
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            // Folgeaufträge (Anreicherung, Bilder) entstehen, während ihr Auslöser noch einen Platz belegt. Sie haben
            // daher einen eigenen Vorrat derselben Größe und eine eigene Grenze je Eigentümer: zwei je erlaubtem
            // Auslöser, sonst nähme etwa maxOpenPerOwner=1 nie einen an, und ein Client belegt den Vorrat nicht allein.
            if (open.values().stream().filter(other -> other.type.followUp() == type.followUp()).count()
                    >= capacity) {
                throw new JobCapacityExceededException(capacity);
            }
            int perOwner = type.followUp() ? 2 * limits.maxOpenPerOwner() : limits.maxOpenPerOwner();
            if (owner != null && open.values().stream()
                    .filter(other -> other.type.followUp() == type.followUp() && owner.equals(other.owner))
                    .count() >= perOwner) {
                throw JobCapacityExceededException.perOwner(perOwner);
            }
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

    /** @throws UnknownJobException wenn es keinen Auftrag mit dieser ID gibt */
    public JobStatus status(String jobId) {
        synchronized (lock) {
            Job job = open.get(jobId);
            if (job != null) {
                return job.snapshot();
            }
        }
        return store.find(jobId).orElseThrow(() -> new UnknownJobException(jobId));
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
     * Löscht einen eigenen beendeten Auftrag samt seinen Diagnoseereignissen. Ein Exportartefakt im Exportverzeichnis
     * gehört zum Auftrag und wird mit ihm unerreichbar; das nächste {@code Exports.purge()} räumt es weg. Kopien, die
     * der Benutzer anderswo gespeichert hat, bleiben unberührt.
     *
     * @throws UnknownJobException      wenn es keinen Auftrag mit dieser ID für diesen Client gibt
     * @throws JobStillRunningException wenn der Auftrag noch wartet oder läuft (vorher {@link #cancel(String, String)})
     */
    public void delete(String jobId, String owner) {
        JobStatus status = requireFinished(requireOwner(status(jobId), owner));
        // Erst die Ereignisse: scheitert das, bleibt der Auftrag und der Fehler kommt beim Aufrufer an.
        events.delete(status.id());
        store.delete(status.id());
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
            if (Objects.equals(status.owner(), owner) && status.state().terminal()
                    && (type == null || status.type() == type)) {
                events.delete(status.id());
                store.delete(status.id());
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
    public boolean cancelSource(String sourceId, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(sourceId, "sourceId");
        List<String> ids;
        synchronized (lock) {
            ids = open.values().stream().filter(job -> job.sourceId.equals(sourceId)).map(job -> job.id).toList();
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
            while (open.values().stream().anyMatch(job -> job.sourceId.equals(sourceId))) {
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
                boolean wasQueued = job.state == JobState.QUEUED;
                if (wasQueued) {
                    queued.remove(job);
                    open.remove(jobId);
                    lock.notifyAll();
                    job.finish(JobState.CANCELLED, null, clock.instant());
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
            if (status.state().terminal() && status.finishedAt() != null && status.finishedAt().isBefore(limit)) {
                // Erst die Ereignisse: scheitert das, bleibt der Auftrag und der nächste Lauf versucht es erneut.
                if (deleteEventsQuietly(status.id())) {
                    store.delete(status.id());
                    removed++;
                }
            }
        }
        return removed;
    }

    private void markInterrupted() {
        Instant now = clock.instant();
        for (JobStatus status : store.all()) {
            if (!status.state().terminal()) {
                store.save(new JobStatus(status.id(), status.type(), status.sourceId(), status.partitionKey(),
                        status.owner(), JobState.FAILED, status.progress(),
                        new JobError(INTERRUPTED, "Der Dienst wurde während des Auftrags beendet; der Auftrag wird "
                                + "nicht fortgesetzt und muss neu gestartet werden"),
                        status.cancelRequested(), status.createdAt(), status.startedAt(), now));
                // Nach der höchsten Nummer, nicht nach der Anzahl: gelöschte Ereignisse hinterlassen Lücken.
                long next = events.events(status.id()).stream().mapToLong(JobEvent::sequence).max().orElse(0) + 1;
                appendQuietly(new JobEvent(status.id(), next, now, status.type(),
                        status.sourceId(), status.partitionKey(), JobEvent.Severity.ERROR, JobEvent.Category.JOB,
                        COMPONENT, null, INTERRUPTED, "Der Dienst wurde während des Auftrags beendet", null, null,
                        null, null));
            }
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
                job.finish(JobState.CANCELLED, null, now);
            } else {
                job.finish(JobState.FAILED, errorOf(cause), now);
            }
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

    private void recordEnd(Job job, JobStatus finished, Throwable cause) {
        JobProgress progress = finished.progress();
        switch (finished.state()) {
            case COMPLETED -> job.emit(true, JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                    "Auftrag abgeschlossen: " + progress.processed() + " Seiten verarbeitet, " + progress.written()
                            + " geschrieben, " + progress.failed() + " gescheitert", null, null, null, null);
            case CANCELLED -> job.emit(true, JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, "CANCELLED",
                    "Auftrag abgebrochen", null, null, null, null);
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
        final String owner;
        final JobTask task;
        final Instant createdAt;
        final AtomicLong discovered = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicLong written = new AtomicLong();
        final AtomicLong failedPages = new AtomicLong();
        volatile JobState state = JobState.QUEUED;
        volatile boolean cancelRequested;
        volatile Instant startedAt;
        volatile Instant deadline;
        Instant finishedAt;
        JobError error;
        final AtomicLong sequence = new AtomicLong();
        volatile String component = COMPONENT;
        volatile String phase;

        Job(String id, JobType type, String sourceId, String partitionKey, String owner, JobTask task,
            Instant createdAt) {
            this.id = id;
            this.type = type;
            this.sourceId = sourceId;
            this.partitionKey = partitionKey;
            this.owner = owner;
            this.task = task;
            this.createdAt = createdAt;
        }

        void finish(JobState end, JobError failure, Instant at) {
            state = end;
            error = failure;
            finishedAt = at;
        }

        JobStatus snapshot() {
            return new JobStatus(id, type, sourceId, partitionKey, owner, state,
                    new JobProgress(discovered.get(), processed.get(), written.get(), failedPages.get()), error,
                    cancelRequested && !state.terminal(), createdAt, startedAt, finishedAt);
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

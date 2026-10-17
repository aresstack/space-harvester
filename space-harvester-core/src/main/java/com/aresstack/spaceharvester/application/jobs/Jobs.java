package com.aresstack.spaceharvester.application.jobs;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
            if (open.size() >= capacity) {
                throw new JobCapacityExceededException(capacity);
            }
            if (owner != null
                    && open.values().stream().filter(other -> owner.equals(other.owner)).count()
                            >= limits.maxOpenPerOwner()) {
                throw JobCapacityExceededException.perOwner(limits.maxOpenPerOwner());
            }
            open.put(job.id, job);
            queued.addLast(job);
            status = job.snapshot();
            store.save(status);
        }
        job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null, "Auftrag angenommen", null, null,
                null, null);
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
        List<JobEvent> mine = new ArrayList<>();
        Map<String, Boolean> owned = new HashMap<>();
        // Großzügig lesen, weil fremde Ereignisse herausfallen; die Ablage hält ohnehin nur beschränkt viele.
        for (JobEvent event : events.recent(Math.max(limit, 1) * 4)) {
            boolean own = owned.computeIfAbsent(event.jobId(), id -> {
                try {
                    return Objects.equals(status(id).owner(), owner);
                } catch (UnknownJobException e) {
                    return false;
                }
            });
            if (own) {
                mine.add(event);
                if (mine.size() >= limit) {
                    break;
                }
            }
        }
        return mine;
    }

    private static JobStatus requireOwner(JobStatus status, String owner) {
        if (!Objects.equals(status.owner(), owner)) {
            throw new UnknownJobException(status.id());
        }
        return status;
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
                    job.finish(JobState.CANCELLED, null, clock.instant());
                }
                JobStatus status = job.snapshot();
                store.save(status);
                job.emit(wasQueued ? JobEvent.Severity.WARNING : JobEvent.Severity.INFO, JobEvent.Category.JOB,
                        COMPONENT, wasQueued ? "CANCELLED" : null, wasQueued ? "Auftrag vor dem Start abgebrochen"
                                : "Abbruch angefordert", null, null, null, null);
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
                store.delete(status.id());
                deleteEventsQuietly(status.id());
                removed++;
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
                appendQuietly(new JobEvent(status.id(), events.events(status.id()).size() + 1L, now, status.type(),
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

    private void deleteEventsQuietly(String jobId) {
        try {
            events.delete(jobId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Diagnoseereignisse nicht entfernt ({0})", e.getClass().getName());
        }
    }

    private void dispatch() {
        List<Job> toStart = new ArrayList<>();
        synchronized (lock) {
            while (running < limits.maxConcurrent() && !queued.isEmpty()) {
                Job job = queued.removeFirst();
                running++;
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
            open.remove(job.id);
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
            case COMPLETED -> job.emit(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, null,
                    "Auftrag abgeschlossen: " + progress.processed() + " Seiten verarbeitet, " + progress.written()
                            + " geschrieben, " + progress.failed() + " gescheitert", null, null, null, null);
            case CANCELLED -> job.emit(JobEvent.Severity.WARNING, JobEvent.Category.JOB, COMPONENT, "CANCELLED",
                    "Auftrag abgebrochen", null, null, null, null);
            default -> {
                JobError error = finished.error();
                FailureDiagnosis.Findings findings = FailureDiagnosis.of(cause, error.code());
                String route = null;
                if (findings.category() == JobEvent.Category.NETWORK
                        || findings.category() == JobEvent.Category.SOURCE) {
                    try {
                        route = routes.routeOf(job.sourceId).orElse(null);
                    } catch (RuntimeException e) {
                        route = "nicht bestimmbar (" + e.getClass().getSimpleName() + ")";
                    }
                }
                // Gewollte Abbrüche ohne Ursache brauchen keinen Stacktrace; alles andere bekommt die ganze Kette.
                boolean plain = cause instanceof JobFailedException && cause.getCause() == null;
                job.emit(JobEvent.Severity.ERROR, findings.category(),
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
            if (event.cause() != null) {
                FailureDiagnosis.Findings findings = FailureDiagnosis.of(event.cause(), event.code());
                detail = findings.detail();
                httpStatus = httpStatus == null ? findings.httpStatus() : httpStatus;
                tls = tls == null ? findings.tlsFinding() : tls;
            }
            emit(event.severity(), event.category(), event.component(), event.code(), event.message(), httpStatus,
                    null, tls, detail);
        }

        void emit(JobEvent.Severity severity, JobEvent.Category category, String eventComponent, String code,
                  String message, Integer httpStatus, String proxyRoute, String tlsFinding, String detail) {
            try {
                appendQuietly(new JobEvent(id, sequence.incrementAndGet(), clock.instant(), type, sourceId,
                        partitionKey, severity, category, eventComponent, phase, code, message, httpStatus,
                        proxyRoute, tlsFinding, detail));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Diagnoseereignis nicht erzeugt ({0})", e.getClass().getName());
            }
        }
    }
}

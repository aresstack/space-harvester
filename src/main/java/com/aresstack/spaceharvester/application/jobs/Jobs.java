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
 * liefen, als gescheitert ({@code INTERRUPTED}); sie werden nicht stillschweigend fortgesetzt.
 */
public class Jobs implements AutoCloseable {

    static final String INTERRUPTED = "INTERRUPTED";
    static final String TIMEOUT = "TIMEOUT";
    static final String UNEXPECTED = "UNEXPECTED";
    static final String SHUTDOWN = "SHUTDOWN";

    private final JobStore store;
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
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        markInterrupted();
    }

    /**
     * Nimmt einen Auftrag an, ohne auf ihn zu warten.
     *
     * @param partitionKey Partition (Space) oder {@code null}
     * @return der Auftrag im Zustand {@link JobState#QUEUED}
     * @throws JobCapacityExceededException wenn bereits {@code maxConcurrent + maxQueued} Aufträge offen sind
     * @throws IllegalStateException         nach {@link #close()}
     */
    public JobStatus submit(JobType type, String sourceId, String partitionKey, JobTask task) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(task, "task");
        Job job = new Job(UUID.randomUUID().toString(), type, sourceId, partitionKey, task, clock.instant());
        JobStatus status;
        synchronized (lock) {
            int capacity = limits.maxConcurrent() + limits.maxQueued();
            if (closed) {
                throw new IllegalStateException("Der Dienst wird beendet und nimmt keine Aufträge mehr an");
            }
            if (open.size() >= capacity) {
                throw new JobCapacityExceededException(capacity);
            }
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
                if (job.state == JobState.QUEUED) {
                    queued.remove(job);
                    open.remove(jobId);
                    job.finish(JobState.CANCELLED, null, clock.instant());
                }
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

    private void markInterrupted() {
        Instant now = clock.instant();
        for (JobStatus status : store.all()) {
            if (!status.state().terminal()) {
                store.save(new JobStatus(status.id(), status.type(), status.sourceId(), status.partitionKey(),
                        JobState.FAILED, status.progress(),
                        new JobError(INTERRUPTED, "Der Dienst wurde während des Auftrags beendet; der Auftrag wird "
                                + "nicht fortgesetzt und muss neu gestartet werden"),
                        status.cancelRequested(), status.createdAt(), status.startedAt(), now));
            }
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
            store.save(job.snapshot());
        }
        dispatch();
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
            return new JobError("SOURCE_" + source.reason().name(),
                    source.getMessage() == null ? source.reason().name() : source.getMessage());
        }
        return new JobError(UNEXPECTED, "Unerwarteter Fehler im Auftrag (" + cause.getClass().getSimpleName() + ")");
    }

    /** Veränderlicher Zustand eines offenen Auftrags; Felder außer den Zählern nur unter {@code lock} ändern. */
    private final class Job implements JobContext {

        final String id;
        final JobType type;
        final String sourceId;
        final String partitionKey;
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

        Job(String id, JobType type, String sourceId, String partitionKey, JobTask task, Instant createdAt) {
            this.id = id;
            this.type = type;
            this.sourceId = sourceId;
            this.partitionKey = partitionKey;
            this.task = task;
            this.createdAt = createdAt;
        }

        void finish(JobState end, JobError failure, Instant at) {
            state = end;
            error = failure;
            finishedAt = at;
        }

        JobStatus snapshot() {
            return new JobStatus(id, type, sourceId, partitionKey, state,
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
    }
}

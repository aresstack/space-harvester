package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.SnapshotRepository;
import java.util.Objects;
import java.util.function.Predicate;

/** Reconcile persisted cross-document analyses after local source changes. */
public final class DependentEnrichments {
    public static final String KIND = "dependent-enrichments";
    private static final String TITLE = "Abhängige Analysen aktualisieren";
    private static final System.Logger LOG = System.getLogger(DependentEnrichments.class.getName());
    private final Enrichments enrichments;
    private final SnapshotRepository snapshots;
    private final BackgroundJobs jobs;
    private final Predicate<String> active;
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.locks.ReentrantLock> sourceLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public DependentEnrichments(Enrichments enrichments, SnapshotRepository snapshots,
                                BackgroundJobs jobs, Predicate<String> active) {
        this.enrichments = Objects.requireNonNull(enrichments);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.jobs = Objects.requireNonNull(jobs);
        this.active = Objects.requireNonNull(active);
        jobs.register(new BackgroundKind(KIND,
                part -> JobTask.blocking(context -> reconcile(part.sourceId(), context))));
    }

    /** Coalesce waiting work; a running job must not hide a subsequent source change. */
    public void changed(String sourceId) {
        if (!enabled(sourceId)) return;
        try {
            jobs.startUnlessWaiting(KIND, TITLE, new BackgroundPart.Draft(sourceId, null));
        } catch (JobCapacityExceededException | IllegalStateException unavailable) {
            LOG.log(System.Logger.Level.INFO, "Dependency reconciliation not queued for {0}: {1}",
                    sourceId, unavailable.getClass().getSimpleName());
        }
    }

    private boolean enabled(String sourceId) {
        return active.test(sourceId) && !jobs.withdrawing(sourceId)
                && !enrichments.dependentProcessorIds(sourceId).isEmpty();
    }

    private void reconcile(String sourceId, JobContext context) throws InterruptedException {
        // Serialize successive passes for the same source without blocking a virtual-thread carrier.
        var lock = sourceLocks.computeIfAbsent(sourceId, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lockInterruptibly();
        try {
            for (String processor : enrichments.dependentProcessorIds(sourceId)) {
                context.checkpoint();
                if (!enabled(sourceId)) return;
                context.phase("Enrichments", TITLE + ": " + processor);
                var pages = enrichments.store().pagesWith(sourceId, processor);
                context.discovered(pages.size());
                for (PageKey key : pages) {
                    context.checkpoint();
                    if (!enabled(sourceId)) return;
                    reconcile(key, processor, context);
                    context.processed(1);
                }
            }
        } finally {
            try {
                enrichments.flush();
            } finally {
                lock.unlock();
            }
        }
    }

    private void reconcile(PageKey key, String processor, JobContext context) {
        // Read the latest persisted result, not a snapshot of all artifacts at job submission.
        var previous = enrichments.store().find(key).stream()
                .filter(result -> processor.equals(result.processorId())).findFirst().orElse(null);
        if (previous == null || !enrichments.outdated(previous)) return;
        var snapshot = snapshots.find(key).orElse(null);
        if (snapshot == null) return;
        context.checkpoint();
        if (!enabled(key.sourceId())) return;
        var result = enrichments.refresh(snapshot, processor);
        context.written(result.computed() - result.failed());
        context.failed(result.failed());
    }
}

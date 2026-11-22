package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * „Neu berechnen“ einer Seite (Fundstelle 21, Architekt 21.11.2026): ein gewöhnlicher Auftrag
 * {@link JobType#ENRICHMENT} mit einer Seite, in der Auftragsliste sichtbar und abbrechbar. Ein Klick wartet nie hinter
 * Hintergrund ({@link Jobs#submitInteractive}). Gerechnet wird wie bisher {@link Enrichments#recompute}, also auch die
 * Prozessoren, die über das Netz rechnen, im Auftrag selbst.
 */
public final class PageRecomputation {

    /** Angabe am Auftrag ({@link EnrichmentsAfterSync#TASK_PARAMETER}): welche Arbeit er tut. */
    public static final String TASK = "recompute-page";
    /** Angabe am Auftrag: die Seite. */
    public static final String ROOT = "root";

    private final Jobs jobs;
    private final Enrichments enrichments;

    public PageRecomputation(Jobs jobs, Enrichments enrichments) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
    }

    /**
     * Rechnet die Analysen der Seite als Auftrag neu und kehrt zurück, wenn er endet. Die Rechte prüft der Aufrufer.
     *
     * @param owner       Auftraggeber, damit die Auftragsliste ihn zeigt
     * @param processorId ein Prozessor oder {@code null} für alle
     * @throws JobCancelledException wenn der Auftrag abgebrochen wurde, vor oder während der Berechnung
     */
    public Enrichments.Run recompute(String owner, PageSnapshot snapshot, String processorId) {
        Objects.requireNonNull(snapshot, "snapshot");
        CompletableFuture<Enrichments.Run> done = new CompletableFuture<>();
        JobStatus status = jobs.submitInteractive(JobType.ENRICHMENT, snapshot.key().sourceId(), snapshot.spaceKey(),
                null, owner, Map.of(EnrichmentsAfterSync.TASK_PARAMETER, TASK, ROOT, snapshot.key().pageId()),
                JobTask.blocking(context -> {
                    try {
                        context.discovered(1);
                        context.checkpoint();
                        Enrichments.Run run = enrichments.recompute(snapshot, processorId);
                        enrichments.flush();
                        context.processed(1);
                        if (run.failed() > 0) {
                            context.failed(1);
                        } else if (run.computed() > 0) {
                            context.written(1);
                        }
                        done.complete(run);
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                        throw e;
                    }
                }));
        // Abgebrochen, bevor er anlief: Die Arbeit läuft nie, der Wartende erfährt es über die Auftragsmeldungen.
        Subscription watching = jobs.subscribe(owner, new JobListener() {
            @Override
            public void changed(JobStatus changed) {
                if (changed.id().equals(status.id()) && changed.state().terminal()) {
                    done.completeExceptionally(new JobCancelledException(status.id()));
                }
            }

            @Override
            public void removed(JobStatus last) {
                if (last.id().equals(status.id())) {
                    done.completeExceptionally(new JobCancelledException(status.id()));
                }
            }
        });
        try {
            if (jobs.status(status.id(), owner).state().terminal()) {
                done.completeExceptionally(new JobCancelledException(status.id()));
            }
            return done.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        } finally {
            watching.close();
        }
    }
}

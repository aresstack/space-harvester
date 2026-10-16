package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Startet Exporte einer Partition aus dem Cache als Auftrag und findet ihre Artefakte. Der Export liest stapelweise
 * und schreibt jede Seite sofort weiter; im Speicher bleiben nur IDs für den Baum.
 *
 * <p>Ein Artefakt ist nur {@code complete}, wenn es aus einer bestätigten Generation stammt und keine Seite fehlt. Ein
 * abgebrochener oder gescheiterter Export veröffentlicht nichts.
 */
public class Exports {

    /** Seiten je Lesestapel. */
    public static final int BATCH_SIZE = 100;

    private final Jobs jobs;
    private final ExportPageReader reader;
    private final ExportArtifactWriter writer;
    private final Clock clock;

    public Exports(Jobs jobs, ExportPageReader reader, ExportArtifactWriter writer, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Nimmt einen Export an, ohne auf ihn zu warten; die Job-ID ist zugleich die Export-ID.
     *
     * @throws com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException wenn zu viele Aufträge offen
     *                                                                                   sind
     */
    public JobStatus start(String sourceId, String partitionKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(partitionKey, "partitionKey");
        return jobs.submit(JobType.EXPORT, sourceId, partitionKey,
                JobTask.blocking(context -> export(context, sourceId, partitionKey)));
    }

    /** Das veröffentlichte Artefakt eines Exports oder leer, solange es keines gibt. */
    public Optional<ExportArtifact> artifact(String exportId) {
        return writer.find(exportId);
    }

    private void export(JobContext context, String sourceId, String partitionKey) {
        Instant startedAt = clock.instant();
        ExportScope scope = reader.scope(sourceId, partitionKey).orElseThrow(() -> new JobFailedException(
                "NOT_CACHED", "Space " + partitionKey + " der Quelle " + sourceId + " liegt nicht im Cache"));
        ExportTreeBuilder tree = new ExportTreeBuilder();
        List<PageFailure> failures = new ArrayList<>();
        long written = 0;
        try (ExportArtifactWriter.Session session = writer.begin(context.jobId(), scope)) {
            String cursor = null;
            do {
                context.checkpoint();
                ExportPageBatch batch = reader.pages(scope, cursor, BATCH_SIZE);
                context.discovered(batch.pages().size() + batch.failures().size());
                for (ExportPage page : batch.pages()) {
                    context.checkpoint();
                    if (!tree.add(page.pageId(), page.metadata().parentPageId())) {
                        throw new JobFailedException("INCONSISTENT_READ",
                                "Seite " + page.pageId() + " wurde doppelt gelesen; der Cache-Stand ist nicht stabil");
                    }
                    session.page(page);
                    written++;
                    context.processed(1);
                    context.written(1);
                }
                failures.addAll(batch.failures());
                context.processed(batch.failures().size());
                context.failed(batch.failures().size());
                cursor = batch.nextCursor();
            } while (cursor != null);
            context.checkpoint();
            ExportTree built = tree.build();
            List<String> warnings = new ArrayList<>(built.warnings());
            if (scope.state() != CacheState.CONFIRMED) {
                warnings.add("Cache-Generation " + scope.generation() + " ist " + scope.state()
                        + "; das Artefakt ist kein bestätigter vollständiger Stand");
            }
            boolean complete = scope.state() == CacheState.CONFIRMED && failures.isEmpty();
            session.publish(built, new ExportSummary(context.jobId(), scope, startedAt, clock.instant(), written,
                    failures, warnings, complete));
        }
    }
}

package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.export.FakeExportPageReader.failure;
import static com.aresstack.spaceharvester.application.export.FakeExportPageReader.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExportsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
    // Direkter Executor: der Auftrag läuft innerhalb von start(), die Tests bleiben deterministisch.
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final RecordingExportArtifactWriter writer = new RecordingExportArtifactWriter();

    private Exports exports(ExportPageReader reader) {
        return new Exports(jobs, reader, writer, clock);
    }

    @Test
    void exportsConfirmedGenerationAsCompleteFlatPagesAndIdTree() {
        Exports exports = exports(FakeExportPageReader.of(CacheState.CONFIRMED,
                page("100", null, "{}"), page("101", "100", "{}"), page("200", null, "{}"),
                page("102", "100", "{}")));

        JobStatus job = exports.start("confluence", "DEV");

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.COMPLETED);
        assertThat(status.progress()).isEqualTo(new JobProgress(4, 4, 4, 0));
        RecordingExportArtifactWriter.Published artifact = writer.published.get(job.id());
        assertThat(artifact.pageIds()).containsExactly("100", "101", "200", "102");
        assertThat(artifact.tree().roots()).containsExactly(
                new ExportTree.Node("100", List.of(new ExportTree.Node("101", List.of()),
                        new ExportTree.Node("102", List.of()))),
                new ExportTree.Node("200", List.of()));
        assertThat(artifact.summary().complete()).isTrue();
        assertThat(artifact.summary().pagesWritten()).isEqualTo(4);
        assertThat(artifact.summary().warnings()).isEmpty();
        assertThat(artifact.summary().scope().generation()).isEqualTo("g1");
        assertThat(exports.artifact(job.id())).get().extracting(ExportArtifact::complete).isEqualTo(true);
    }

    @Test
    void readsInBatchesUntilTheCursorEnds() {
        FakeExportPageReader reader = FakeExportPageReader.generated(Exports.BATCH_SIZE * 2 + 1);

        JobStatus job = exports(reader).start("confluence", "DEV");

        assertThat(reader.batches()).isEqualTo(3);
        assertThat(writer.published.get(job.id()).summary().pagesWritten()).isEqualTo(Exports.BATCH_SIZE * 2 + 1);
    }

    @Test
    void unconfirmedGenerationIsNeverComplete() {
        for (CacheState state : List.of(CacheState.INCOMPLETE, CacheState.STALE)) {
            RecordingExportArtifactWriter.Published artifact = writer.published.get(
                    exports(FakeExportPageReader.of(state, page("1", null, "{}"))).start("confluence", "DEV").id());

            assertThat(artifact.summary().complete()).isFalse();
            assertThat(artifact.summary().warnings()).anyMatch(w -> w.contains(state.name()));
        }
    }

    @Test
    void missingPagesMakeTheArtifactIncompleteAndAreListed() {
        Exports exports = exports(FakeExportPageReader.of(CacheState.CONFIRMED,
                page("1", null, "{}"), failure("2"), page("3", "2", "{}")));

        JobStatus job = exports.start("confluence", "DEV");

        assertThat(jobs.status(job.id()).progress()).isEqualTo(new JobProgress(3, 3, 2, 1));
        ExportSummary summary = writer.published.get(job.id()).summary();
        assertThat(summary.complete()).isFalse();
        assertThat(summary.failures()).extracting(PageFailure::pageId).containsExactly("2");
        // Seite 3 hängt an der fehlenden Seite 2 und wird Wurzel; jede Seite im Baum steht im Seiten-Array.
        assertThat(writer.published.get(job.id()).tree().roots()).extracting(ExportTree.Node::pageId)
                .containsExactly("1", "3");
        assertThat(summary.warnings()).anyMatch(w -> w.contains("Elternseite 2"));
    }

    @Test
    void notCachedPartitionFailsWithoutArtifact() {
        JobStatus job = exports(FakeExportPageReader.of(CacheState.CONFIRMED)).start("confluence", "OTHER");

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("NOT_CACHED");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void readFailureDiscardsThePartialArtifact() {
        FakeExportPageReader pages = FakeExportPageReader.generated(Exports.BATCH_SIZE * 3);
        ExportPageReader failing = new ExportPageReader() {
            @Override
            public Optional<ExportScope> scope(String sourceId, String partitionKey) {
                return pages.scope(sourceId, partitionKey);
            }

            @Override
            public ExportPageBatch pages(ExportScope scope, String cursor, int limit) {
                if (cursor != null) {
                    throw new JobFailedException("GENERATION_GONE", "Generation g1 ist nicht mehr lesbar");
                }
                return pages.pages(scope, cursor, limit);
            }
        };

        JobStatus job = exports(failing).start("confluence", "DEV");

        assertThat(jobs.status(job.id()).error().code()).isEqualTo("GENERATION_GONE");
        assertThat(writer.published).isEmpty();
        assertThat(writer.discarded).containsExactly(job.id());
        assertThat(new Exports(jobs, failing, writer, clock).artifact(job.id())).isEmpty();
    }

    @Test
    void duplicatePageFailsInsteadOfPublishingAnInconsistentArtifact() {
        JobStatus job = exports(FakeExportPageReader.of(CacheState.CONFIRMED,
                page("1", null, "{}"), page("1", null, "{}"))).start("confluence", "DEV");

        assertThat(jobs.status(job.id()).error().code()).isEqualTo("INCONSISTENT_READ");
        assertThat(writer.published).isEmpty();
    }

    @Test
    void cancelledExportPublishesNothing() {
        List<Runnable> deferred = new ArrayList<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), deferred::add, new JobLimits(1, 5, Duration.ofHours(1)), clock);
        FakeExportPageReader pages = FakeExportPageReader.generated(Exports.BATCH_SIZE * 3);
        AtomicReference<String> jobId = new AtomicReference<>();
        ExportPageReader cancelling = new ExportPageReader() {
            @Override
            public Optional<ExportScope> scope(String sourceId, String partitionKey) {
                return pages.scope(sourceId, partitionKey);
            }

            @Override
            public ExportPageBatch pages(ExportScope scope, String cursor, int limit) {
                if (cursor != null) {
                    // Abbruch während des zweiten Stapels, wie von einer zweiten Anfrage.
                    jobs.cancel(jobId.get());
                }
                return pages.pages(scope, cursor, limit);
            }
        };

        jobId.set(new Exports(jobs, cancelling, writer, clock).start("confluence", "DEV").id());
        deferred.removeFirst().run();

        JobStatus status = jobs.status(jobId.get());
        assertThat(status.state()).isEqualTo(JobState.CANCELLED);
        assertThat(status.progress().written()).isEqualTo(Exports.BATCH_SIZE);
        assertThat(writer.published).isEmpty();
        assertThat(writer.discarded).containsExactly(jobId.get());
    }
}

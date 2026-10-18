package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentsAfterSync;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Anreicherungen nach bestätigten Abgleichen: nachziehen, entfernen und den Abgleich nie scheitern lassen. */
class SyncEnrichmentsTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", null)
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySyncStore store = new InMemorySyncStore();
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add,
            new JobLimits(2, 10, Duration.ofHours(1)), FakeSource.CLOCK);
    private Set<String> unreachable = Set.of();
    private final PageContentFetcher fetcher = (sourceId, pageId) -> {
        if (unreachable.contains(pageId)) {
            throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null);
        }
        PageMetadata page = source.pageMetadata(pageId);
        return PageSnapshot.of(sourceId, page, new NativePayload(("Seite " + pageId + " Version " + page.version())
                .getBytes(StandardCharsets.UTF_8), "text/plain", "test-text"), FakeSource.CLOCK.instant());
    };
    private final InMemoryEnrichmentStore results = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(
            snapshot -> Optional.of(new PageText("test/v1",
                    new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of())),
            List.of(new OutlineProcessor()), results, FakeSource.CLOCK);

    @Test
    void everyStoredPageIsEnrichedAfterTheRun() {
        JobStatus job = run(new EnrichmentsAfterSync(enrichments, store.repository));

        assertThat(job.state()).isEqualTo(JobState.COMPLETED);
        for (String pageId : List.of("1", "2", "3")) {
            assertThat(results.find(new PageKey("wiki", pageId))).singleElement()
                    .extracting(Enrichment::processorId, Enrichment::status)
                    .containsExactly("outline", Enrichment.Status.OK);
        }
    }

    @Test
    void changedPagesAreRecomputedAndRemovedPagesLoseTheirResults() {
        SyncCommitted hook = new EnrichmentsAfterSync(enrichments, store.repository);
        run(hook);
        source.put("3", "DOC", null, "8", null).remove("2");

        run(hook);

        assertThat(results.find(new PageKey("wiki", "2"))).isEmpty();
        assertThat(results.find(new PageKey("wiki", "3"))).singleElement()
                .extracting(Enrichment::revision).isEqualTo("8");
        assertThat(enrichments.view(store.repository.find(new PageKey("wiki", "3")).orElseThrow()))
                .extracting(Enrichments.View::freshness).containsExactly(Enrichment.Freshness.CURRENT);
    }

    @Test
    void aPageThatCouldNotBeFetchedGetsNoResultAndTheOthersDo() {
        unreachable = Set.of("1");

        JobStatus job = run(new EnrichmentsAfterSync(enrichments, store.repository));

        assertThat(job.error().code()).isEqualTo(SpaceSyncJobs.PARTIAL);
        assertThat(results.find(new PageKey("wiki", "1"))).isEmpty();
        assertThat(results.find(new PageKey("wiki", "2"))).hasSize(1);
    }

    @Test
    void failingFollowUpWorkNeverFailsTheSync() {
        List<String> later = new java.util.ArrayList<>();

        JobStatus job = run(run -> {
            throw new IllegalStateException("Ablage voll");
        }, run -> later.add(run.confirmed().spaceKey()));

        assertThat(job.state()).isEqualTo(JobState.COMPLETED);
        assertThat(later).containsExactly("DOC");
        assertThat(store.confirmed("wiki", "DOC")).isPresent();
    }

    @Test
    void withoutACacheNothingHappens() {
        JobStatus job = run(new EnrichmentsAfterSync(enrichments, null));

        assertThat(job.state()).isEqualTo(JobState.COMPLETED);
        assertThat(results.find(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void enrichmentIsItsOwnJobAndTheSyncDoesNotWaitForIt() {
        SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK,
                List.of(new EnrichmentsAfterSync(enrichments, store.repository, jobs)));
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        boolean sawSyncDoneBeforeEnrichment = false;
        while (!queued.isEmpty()) {
            List<JobStatus> pending = jobs.list("client:a", JobType.ENRICHMENT);
            if (jobs.status(accepted.id()).state() == JobState.COMPLETED && pending.size() == 1
                    && !pending.get(0).state().terminal()) {
                sawSyncDoneBeforeEnrichment = true;
                assertThat(results.find(new PageKey("wiki", "1"))).isEmpty();
            }
            queued.poll().run();
        }

        assertThat(sawSyncDoneBeforeEnrichment).isTrue();
        JobStatus enrichment = jobs.list("client:a", JobType.ENRICHMENT).get(0);
        assertThat(enrichment.state()).isEqualTo(JobState.COMPLETED);
        assertThat(enrichment.sourceId()).isEqualTo("wiki");
        assertThat(enrichment.partitionKey()).isEqualTo("DOC");
        // drei Seiten mal ein Prozessor
        assertThat(enrichment.progress().discovered()).isEqualTo(3);
        assertThat(enrichment.progress().processed()).isEqualTo(3);
        assertThat(enrichment.progress().written()).isEqualTo(3);
        assertThat(results.find(new PageKey("wiki", "3"))).hasSize(1);
    }

    @Test
    void aCancelledEnrichmentLeavesTheSyncConfirmed() {
        SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK,
                List.of(new EnrichmentsAfterSync(enrichments, store.repository, jobs)));
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        while (!queued.isEmpty() && jobs.list("client:a", JobType.ENRICHMENT).isEmpty()) {
            queued.poll().run();
        }
        JobStatus enrichment = jobs.list("client:a", JobType.ENRICHMENT).get(0);

        jobs.cancel(enrichment.id(), "client:a");
        while (!queued.isEmpty()) {
            queued.poll().run();
        }

        assertThat(jobs.status(enrichment.id()).state()).isEqualTo(JobState.CANCELLED);
        assertThat(syncs.status(accepted.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
        assertThat(store.confirmed("wiki", "DOC")).isPresent();
        assertThat(results.find(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void provisioningCountsOnlyPagesTheCallerMayReadAndLeavesWritingToTheExport() {
        SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK, List.of());
        JobStatus job = jobs.submit(JobType.EXPORT, "wiki", "DOC", "client:a", com.aresstack.spaceharvester
                .application.jobs.JobTask.blocking(context -> syncs.provision(context, "wiki", "DOC",
                        header -> !header.key().pageId().equals("2"))));
        while (!queued.isEmpty()) {
            queued.poll().run();
        }

        assertThat(jobs.status(job.id()).progress())
                .isEqualTo(new com.aresstack.spaceharvester.application.jobs.JobProgress(2, 2, 0, 0));
    }

    private JobStatus run(SyncCommitted... afterCommit) {
        SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK,
                List.of(afterCommit));
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
        return syncs.status(accepted.id(), "client:a").status();
    }
}

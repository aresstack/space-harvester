package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Sync-Läufe als Aufträge: Ausgang, Delta, Zähler, Abbruch, ein Lauf je Space und Sichtbarkeit nur für den Auslöser. */
class SpaceSyncJobsTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySyncStore store = new InMemorySyncStore();
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add,
            new JobLimits(2, 10, Duration.ofHours(1)), FakeSource.CLOCK);
    /** Wird vor jedem Inhaltsabruf mit der Seiten-ID aufgerufen. */
    private Consumer<String> beforeFetch = pageId -> {
    };
    private final PageContentFetcher fetcher = (sourceId, pageId) -> {
        beforeFetch.accept(pageId);
        PageMetadata page = source.pageMetadata(pageId);
        return PageSnapshot.of(sourceId, page, new NativePayload(("{\"id\":\"" + pageId + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    };
    private final SpaceSyncJobs syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK);

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    @Test
    void aCompleteRunConfirmsAGenerationAndReportsItsDelta() {
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");

        assertThat(accepted.type()).isEqualTo(JobType.SYNC);
        assertThat(accepted.partitionKey()).isEqualTo("DOC");
        assertThat(syncs.status(accepted.id(), "client:a").summary()).isNull();
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(accepted.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.status().progress()).isEqualTo(new JobProgress(3, 3, 3, 0));
        SyncSummary summary = done.summary();
        assertThat(summary.inventory()).isEqualTo(Inventory.Status.COMPLETE);
        assertThat(summary.added()).isEqualTo(3);
        assertThat(summary.generation()).isEqualTo(1);
        assertThat(summary.complete()).isTrue();
        assertThat(summary.spaceFingerprint()).isEqualTo(
                store.confirmed("wiki", "DOC").orElseThrow().spaceFingerprint());
        assertThat(store.repository.find(new PageKey("wiki", "2"))).isPresent();
    }

    @Test
    void aReconcileWithoutFirstLoadCompletesWithoutLoadingUnknownPages() {
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a", false);
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(accepted.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.summary().notLoaded()).isEqualTo(3);
        assertThat(done.summary().complete()).isFalse();
        assertThat(store.repository.find(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void aScheduledReconcileStartsSyncsAndSkipsWhileOneIsRunning() {
        try (Reconciliation reconciliation = new Reconciliation(syncs, null, FakeSource.CLOCK)) {
            reconciliation.schedule("wiki", "DOC", Duration.ofHours(1), true);
            Reconciliation.Entry entry = reconciliation.entry("wiki", "DOC");

            reconciliation.due(entry);
            Reconciliation.Schedule started = reconciliation.schedules().get(0);
            reconciliation.due(entry);
            Reconciliation.Schedule skipped = reconciliation.schedules().get(0);
            runQueued();

            assertThat(started.lastOutcome()).isEqualTo(Reconciliation.Outcome.STARTED);
            assertThat(skipped.lastOutcome()).isEqualTo(Reconciliation.Outcome.ALREADY_RUNNING);
            assertThat(skipped.lastJobId()).isEqualTo(started.lastJobId());
            assertThat(skipped.nextAt()).isEqualTo(FakeSource.CLOCK.instant().plus(Duration.ofHours(1)));
            assertThat(store.confirmed("wiki", "DOC")).isPresent();
            assertThat(reconciliation.cancel("wiki", "DOC")).isTrue();
            assertThat(reconciliation.schedules()).isEmpty();
        }
    }

    @Test
    void aReconcileIntervalBelowOneMinuteIsRejected() {
        try (Reconciliation reconciliation = new Reconciliation(syncs, null, FakeSource.CLOCK)) {
            assertThatThrownBy(() -> reconciliation.schedule("wiki", "DOC",
                    Duration.ofSeconds(30), true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aRepeatedRunOnlyCountsWhatChanged() {
        syncs.start("wiki", "DOC", "client:a");
        runQueued();
        source.put("1", "DOC", null, "3", "api,veraltet").put("3", "DOC", null, "8", null).remove("2");

        JobStatus second = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(second.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.summary()).extracting(SyncSummary::contentChanged, SyncSummary::attributesChanged,
                SyncSummary::removed, SyncSummary::withheld, SyncSummary::unchanged).containsExactly(1, 1, 0, 1, 0);
        assertThat(done.summary().generation()).isEqualTo(2);
        assertThat(done.status().progress().written()).isEqualTo(3);
        assertThat(store.repository.find(new PageKey("wiki", "2"))).isEmpty();
    }

    @Test
    void anAbortedInventoryFailsWithoutConfirmingAndLeavesTheCacheUntouched() {
        source.failListingAtCall(0);

        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(accepted.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.FAILED);
        assertThat(done.status().error().code()).isEqualTo(SpaceSyncJobs.INCOMPLETE);
        assertThat(done.summary().inventory()).isEqualTo(Inventory.Status.INCOMPLETE);
        assertThat(done.summary().generation()).isNull();
        assertThat(done.summary().notConfirmed()).isNotBlank();
        assertThat(store.confirmed("wiki", "DOC")).isEmpty();
        assertThat(store.repository.find(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void aNewPageWithoutContentConfirmsAnIncompleteGenerationAndFailsTheJob() {
        beforeFetch = pageId -> {
            if (pageId.equals("1")) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                        null);
            }
        };

        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(accepted.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.FAILED);
        assertThat(done.status().error().code()).isEqualTo(SpaceSyncJobs.PARTIAL);
        assertThat(done.status().progress().failed()).isEqualTo(1);
        assertThat(done.summary().generation()).isEqualTo(1);
        assertThat(done.summary().complete()).isFalse();
        assertThat(done.summary().missingPageIds()).containsExactly("1");
        SyncBaseline confirmed = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(confirmed.complete()).isFalse();
        assertThat(confirmed.pages()).containsOnlyKeys("2", "3");
        assertThat(store.repository.find(new PageKey("wiki", "1"))).isEmpty();

        beforeFetch = pageId -> {
        };
        JobStatus retry = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        SpaceSyncJobs.SyncJob repaired = syncs.status(retry.id(), "client:a");
        assertThat(repaired.status().state()).isEqualTo(JobState.COMPLETED);
        assertThat(repaired.summary().added()).isEqualTo(1);
        assertThat(repaired.summary().complete()).isTrue();
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().complete()).isTrue();
        assertThat(store.repository.find(new PageKey("wiki", "1"))).isPresent();
    }

    @Test
    void aFailedRevisionUpdateKeepsTheOldStateAsStaleAndFailsTheJob() {
        syncs.start("wiki", "DOC", "client:a");
        runQueued();
        source.put("3", "DOC", null, "8", null);
        beforeFetch = pageId -> {
            throw new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                    null);
        };

        JobStatus second = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(second.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.FAILED);
        assertThat(done.status().error().code()).isEqualTo(SpaceSyncJobs.PARTIAL);
        assertThat(done.summary().failedPageIds()).containsExactly("3");
        SyncBaseline confirmed = store.confirmed("wiki", "DOC").orElseThrow();
        assertThat(confirmed.generation()).isEqualTo(2);
        assertThat(confirmed.stalePageIds()).containsExactly("3");
        assertThat(confirmed.pages().get("3").version()).isEqualTo("7");
        assertThat(store.repository.find(new PageKey("wiki", "3")).orElseThrow().revision()).isEqualTo("7");

        beforeFetch = pageId -> {
        };
        JobStatus third = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        assertThat(syncs.status(third.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().stalePageIds()).isEmpty();
        assertThat(store.repository.find(new PageKey("wiki", "3")).orElseThrow().revision()).isEqualTo("8");
    }

    @Test
    void aConflictingRunFailsAndWritesNothing() {
        store.failNextCommit = true;

        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(accepted.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.FAILED);
        assertThat(done.status().error().code()).isEqualTo(SpaceSyncJobs.CONFLICT);
        assertThat(done.summary()).isNull();
        assertThat(store.repository.find(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void cancellingARunningSyncDiscardsEverythingStaged() {
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        beforeFetch = pageId -> {
            if (pageId.equals("2")) {
                syncs.cancel(accepted.id(), "client:a");
            }
        };

        runQueued();

        SpaceSyncJobs.SyncJob done = syncs.status(accepted.id(), "client:a");
        assertThat(done.status().state()).isEqualTo(JobState.CANCELLED);
        assertThat(done.status().progress().written()).isEqualTo(2);
        assertThat(store.confirmed("wiki", "DOC")).isEmpty();
        assertThat(store.repository.find(new PageKey("wiki", "1"))).isEmpty();
    }

    @Test
    void onlyOneSyncPerSpaceWaitsOrRuns() {
        JobStatus first = syncs.start("wiki", "DOC", "client:a");

        assertThatThrownBy(() -> syncs.start("wiki", "DOC", "client:b"))
                .isInstanceOfSatisfying(SyncAlreadyRunningException.class,
                        e -> assertThat(e.jobId()).isEqualTo(first.id()));
        JobStatus other = syncs.start("wiki", "OPS", "client:b");
        runQueued();

        assertThat(syncs.start("wiki", "DOC", "client:b").id()).isNotEqualTo(first.id());
        assertThat(other.partitionKey()).isEqualTo("OPS");
    }

    @Test
    void onlyTheTriggeringClientSeesItsSyncAndOnlySyncJobsAreSyncs() {
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        JobStatus export = jobs.submit(JobType.EXPORT, "wiki", "DOC", "client:a", JobTask.blocking(context -> {
        }));

        assertThatThrownBy(() -> syncs.status(accepted.id(), "client:b")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> syncs.cancel(accepted.id(), "client:b")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> syncs.status(export.id(), "client:a")).isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> syncs.cancel(export.id(), "client:a")).isInstanceOf(UnknownJobException.class);
        assertThat(jobs.status(export.id()).cancelRequested()).isFalse();
        assertThat(jobs.status(export.id()).state()).isNotEqualTo(JobState.CANCELLED);
    }

    @Test
    void withoutStoreOrFetcherNoSyncIsAccepted() {
        SpaceSyncJobs withoutStore = new SpaceSyncJobs(jobs, source.queries(), null, fetcher, FakeSource.CLOCK);
        SpaceSyncJobs withoutFetcher = new SpaceSyncJobs(jobs, source.queries(), store, null, FakeSource.CLOCK);

        assertThat(withoutStore.available()).isFalse();
        assertThat(withoutFetcher.available()).isFalse();
        assertThatThrownBy(() -> withoutStore.start("wiki", "DOC", "client:a"))
                .isInstanceOf(SyncUnavailableException.class);
        assertThatThrownBy(() -> withoutFetcher.start("wiki", "DOC", "client:a"))
                .isInstanceOf(SyncUnavailableException.class);
        assertThatThrownBy(() -> syncs.start("other", "DOC", "client:a")).isInstanceOf(UnknownSourceException.class);
        assertThat(queued).isEmpty();
    }
}

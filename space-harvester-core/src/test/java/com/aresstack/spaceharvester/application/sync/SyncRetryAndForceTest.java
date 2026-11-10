package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobActionNotAllowedException;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * „Nur Fehler neu versuchen“ und „Neu erzwingen“ für Abgleiche (ADR 0025): beides im selben Eintrag, kein neuer
 * Auftrag; „Nur Fehler“ ohne Inventur, „Neu erzwingen“ lädt auch Unverändertes neu.
 */
class SyncRetryAndForceTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", null)
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final InMemorySyncStore store = new InMemorySyncStore();
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final InMemoryJobStore jobStore = new InMemoryJobStore();
    private final JobLimits limits = new JobLimits(2, 10, Duration.ofHours(1));
    private final Jobs jobs = new Jobs(jobStore, queued::add, limits, FakeSource.CLOCK);
    private final Set<String> failing = new HashSet<>();
    private final List<String> fetched = new ArrayList<>();
    private final PageContentFetcher fetcher = (sourceId, pageId) -> {
        fetched.add(pageId);
        if (failing.contains(pageId)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "kein Netz", null);
        }
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

    /** Ein vollständiger Abgleich, der Seite 3 nicht laden konnte. */
    private JobStatus syncWithPage3Failing() {
        failing.add("3");
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a", SyncMode.FULL);
        runQueued();
        failing.clear();
        fetched.clear();
        JobStatus done = syncs.status(accepted.id(), "client:a").status();
        assertThat(done.error().code()).isEqualTo(SpaceSyncJobs.PARTIAL);
        assertThat(done.lifecycle().actions())
                .containsExactly(JobAction.RETRY_FAILED, JobAction.FORCE_REFRESH, JobAction.DELETE);
        return done;
    }

    @Test
    void onlyTheFailedPageIsLoadedInTheSameEntryWithoutAnInventory() {
        JobStatus partial = syncWithPage3Failing();
        // Jede Listung würde scheitern: Nachladen darf keine Inventur machen.
        source.failListingAtCall(0);

        SpaceSyncJobs.SyncJob retried = syncs.retryFailed(partial.id(), "client:a");
        runQueued();

        assertThat(retried.status().id()).isEqualTo(partial.id());
        JobStatus done = syncs.status(partial.id(), "client:a").status();
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress()).isEqualTo(new JobProgress(1, 1, 1, 0));
        assertThat(fetched).containsExactly("3");
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().complete()).isTrue();
        assertThat(store.repository.find(new PageKey("wiki", "3"))).isPresent();
        assertThat(jobs.list("client:a", JobType.SYNC)).hasSize(1);
    }

    @Test
    void aPageThatFailsAgainLeavesTheSyncFinishedWithErrors() {
        JobStatus partial = syncWithPage3Failing();
        failing.add("3");

        syncs.retryFailed(partial.id(), "client:a");
        runQueued();

        JobStatus done = syncs.status(partial.id(), "client:a").status();
        assertThat(done.error().code()).isEqualTo(SpaceSyncJobs.PARTIAL);
        assertThat(done.progress().failed()).isEqualTo(1);
        assertThat(done.lifecycle().allows(JobAction.RETRY_FAILED)).isTrue();

        failing.clear();
        syncs.retryFailed(partial.id(), "client:a");
        runQueued();
        assertThat(syncs.status(partial.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void afterARestartTheConfirmedGenerationNamesThePagesToRetry() {
        JobStatus partial = syncWithPage3Failing();
        Jobs restarted = new Jobs(jobStore, queued::add, limits, FakeSource.CLOCK);
        SpaceSyncJobs second = new SpaceSyncJobs(restarted, source.queries(), store, fetcher, FakeSource.CLOCK);

        second.retryFailed(partial.id(), "client:a");
        runQueued();

        assertThat(fetched).containsExactly("3");
        assertThat(second.status(partial.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void forcingReloadsEveryPageInTheSameEntryAndKeepsTheOldStateUntilItIsDone() {
        JobStatus first = syncs.start("wiki", "DOC", "client:a", SyncMode.FULL);
        runQueued();
        fetched.clear();
        long generation = store.confirmed("wiki", "DOC").orElseThrow().generation();
        assertThat(syncs.status(first.id(), "client:a").status().lifecycle().allows(JobAction.FORCE_REFRESH))
                .isTrue();

        syncs.forceRefresh(first.id(), "client:a");
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(generation);
        runQueued();

        assertThat(fetched).containsExactlyInAnyOrder("1", "2", "3");
        JobStatus done = syncs.status(first.id(), "client:a").status();
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress().written()).isEqualTo(3);
        assertThat(store.confirmed("wiki", "DOC").orElseThrow().generation()).isEqualTo(generation + 1);
        assertThat(jobs.list("client:a", JobType.SYNC)).hasSize(1);
    }

    @Test
    void aForcedRefreshPausedBeforeARestartResumesAsAForcedRefresh() {
        JobStatus first = syncs.start("wiki", "DOC", "client:a", SyncMode.FULL);
        runQueued();
        fetched.clear();
        syncs.forceRefresh(first.id(), "client:a");
        syncs.pause(first.id(), "client:a");
        runQueued();
        assertThat(syncs.status(first.id(), "client:a").status().state()).isEqualTo(JobState.PAUSED);

        Jobs restarted = new Jobs(jobStore, queued::add, limits, FakeSource.CLOCK);
        SpaceSyncJobs second = new SpaceSyncJobs(restarted, source.queries(), store, fetcher, FakeSource.CLOCK);
        second.resume(first.id(), "client:a");
        runQueued();

        assertThat(fetched).containsExactlyInAnyOrder("1", "2", "3");
        assertThat(second.status(first.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void aRetryPausedBeforeARestartResumesWithoutAnInventory() {
        JobStatus partial = syncWithPage3Failing();
        syncs.retryFailed(partial.id(), "client:a");
        syncs.pause(partial.id(), "client:a");
        runQueued();

        Jobs restarted = new Jobs(jobStore, queued::add, limits, FakeSource.CLOCK);
        SpaceSyncJobs second = new SpaceSyncJobs(restarted, source.queries(), store, fetcher, FakeSource.CLOCK);
        source.failListingAtCall(0);
        second.resume(partial.id(), "client:a");
        runQueued();

        assertThat(fetched).containsExactly("3");
        assertThat(second.status(partial.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void aCatalogOnlySyncHasNothingToForce() {
        JobStatus catalog = syncs.start("wiki", "DOC", "client:a", SyncMode.CATALOG_ONLY);
        runQueued();

        assertThatThrownBy(() -> syncs.forceRefresh(catalog.id(), "client:a"))
                .isInstanceOf(JobActionNotAllowedException.class);
        assertThat(fetched).isEmpty();
    }
}

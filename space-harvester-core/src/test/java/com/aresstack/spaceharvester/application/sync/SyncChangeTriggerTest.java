package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import com.aresstack.spaceharvester.persistence.lucene.LuceneSyncBaselineStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0030, Bedingung 1 und 2 des Architekten: Jeder Weg der Synchronisierung schreibt seine Zeilen mit seinem Auslöser,
 * nie {@link ChangeTrigger#UNKNOWN}; der Auslöser steht am Auftrag und übersteht Neustart und Fortsetzen.
 */
class SyncChangeTriggerTest {

    @TempDir
    Path directory;

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "api")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final InMemoryJobStore jobStore = new InMemoryJobStore();
    private final JobLimits limits = new JobLimits(2, 10, Duration.ofHours(1));
    private final Set<String> failing = new HashSet<>();
    private final PageContentFetcher fetcher = (sourceId, pageId) -> {
        if (failing.contains(pageId)) {
            throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "kein Netz", null);
        }
        PageMetadata page = source.pageMetadata(pageId);
        return PageSnapshot.of(sourceId, page, new NativePayload(("{\"id\":\"" + pageId + "\",\"v\":\""
                + page.version() + "\"}").getBytes(StandardCharsets.UTF_8), "application/json",
                "confluence-content/v1"), FakeSource.CLOCK.instant());
    };
    private LucenePageCache cache;
    private LuceneSyncBaselineStore store;
    private Jobs jobs;
    private SpaceSyncJobs syncs;

    @BeforeEach
    void open() {
        cache = LucenePageCache.open(directory);
        store = new LuceneSyncBaselineStore(cache);
        jobs = new Jobs(jobStore, queued::add, limits, FakeSource.CLOCK);
        syncs = new SpaceSyncJobs(jobs, source.queries(), store, fetcher, FakeSource.CLOCK);
    }

    @AfterEach
    void close() {
        cache.close();
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    /** Erste manuelle Synchronisierung; danach hat Seite 1 eine neue Version. */
    private int loadedThenChanged() {
        syncs.start("wiki", "DOC", "client:a");
        runQueued();
        source.change("1", "4", 5);
        return cache.changeEntries("wiki").size();
    }

    private List<ChangeEntry> since(int before) {
        List<ChangeEntry> entries = cache.changeEntries("wiki");
        return entries.subList(before, entries.size());
    }

    private static void allBy(List<ChangeEntry> rows, ChangeTrigger trigger) {
        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(ChangeEntry::trigger).containsOnly(trigger);
    }

    @Test
    void aManualSyncWritesManualRowsAndKeepsItsTriggerOnTheJob() {
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a");
        runQueued();

        assertThat(accepted.parameters()).containsEntry(ChangeTrigger.PARAMETER, "MANUAL");
        allBy(cache.changeEntries("wiki"), ChangeTrigger.MANUAL);
        assertThat(cache.changeEntries("wiki")).extracting(ChangeEntry::kind).containsOnly(ChangeKind.NEW);
    }

    @Test
    void aManualCatchUpIsManual() {
        int before = loadedThenChanged();

        JobStatus job = syncs.catchUp("wiki", "DOC", "client:a", null);
        runQueued();

        assertThat(job.parameters()).containsEntry(ChangeTrigger.PARAMETER, "MANUAL");
        allBy(since(before), ChangeTrigger.MANUAL);
    }

    @Test
    void catchingUpOnStartIsStartup() {
        int before = loadedThenChanged();

        List<JobStatus> started = syncs.catchUpAll("client:a", sourceId -> SyncMode.SYNC_CACHE);
        runQueued();

        assertThat(started).singleElement()
                .satisfies(job -> assertThat(job.parameters()).containsEntry(ChangeTrigger.PARAMETER, "STARTUP"));
        allBy(since(before), ChangeTrigger.STARTUP);
    }

    @Test
    void catchingUpBeforeASearchIsSearch() {
        int before = loadedThenChanged();

        syncs.catchUpAll("client:a", ChangeTrigger.SEARCH, sourceId -> SyncMode.SYNC_CACHE);
        runQueued();

        allBy(since(before), ChangeTrigger.SEARCH);
    }

    @Test
    void aScheduledSyncAndAScheduledInventoryAreSchedule() {
        int before = loadedThenChanged();
        try (Reconciliation reconciliation = new Reconciliation(syncs, null, FakeSource.CLOCK)) {
            reconciliation.schedule("wiki", "DOC", Duration.ofHours(1), SyncMode.SYNC_CACHE);
            reconciliation.due(reconciliation.entry("wiki", "DOC"));
            runQueued();
        }
        allBy(since(before), ChangeTrigger.SCHEDULE);

        source.change("2", "2", 1);
        int second = cache.changeEntries("wiki").size();
        syncs.start("wiki", "DOC", null, SyncMode.SYNC_CACHE, ChangeTrigger.SCHEDULE);
        runQueued();
        allBy(since(second), ChangeTrigger.SCHEDULE);
    }

    @Test
    void anAppliedChangeEventIsEvent() {
        int before = loadedThenChanged();

        List<ChangeEvent> deferred = syncs.applyEvents("wiki",
                List.of(new ChangeEvent(ChangeEvent.Kind.CHANGED, "1", "DOC", "4")));

        assertThat(deferred).isEmpty();
        allBy(since(before), ChangeTrigger.EVENT);
        assertThat(since(before)).extracting(ChangeEntry::kind).contains(ChangeKind.CONTENT_CHANGED);
    }

    @Test
    void retryingFailedPagesIsRetryAndTheJobStaysManual() {
        failing.add("3");
        JobStatus partial = syncs.start("wiki", "DOC", "client:a", SyncMode.FULL);
        runQueued();
        failing.clear();
        int before = cache.changeEntries("wiki").size();

        syncs.retryFailed(partial.id(), "client:a");
        runQueued();

        allBy(since(before), ChangeTrigger.RETRY);
        assertThat(since(before)).extracting(ChangeEntry::pageId).containsOnly("3");
        assertThat(syncs.status(partial.id(), "client:a").status().parameters())
                .containsEntry(ChangeTrigger.PARAMETER, "MANUAL");
    }

    @Test
    void aPausedManualSyncResumedAfterARestartStaysManual() {
        JobStatus accepted = syncs.start("wiki", "DOC", "client:a", SyncMode.FULL);
        syncs.pause(accepted.id(), "client:a");
        runQueued();
        assertThat(syncs.status(accepted.id(), "client:a").status().state()).isEqualTo(JobState.PAUSED);

        Jobs restarted = new Jobs(jobStore, queued::add, limits, FakeSource.CLOCK);
        SpaceSyncJobs second = new SpaceSyncJobs(restarted, source.queries(), store, fetcher, FakeSource.CLOCK);
        second.resume(accepted.id(), "client:a");
        runQueued();

        assertThat(second.status(accepted.id(), "client:a").status().state()).isEqualTo(JobState.COMPLETED);
        allBy(cache.changeEntries("wiki"), ChangeTrigger.MANUAL);
    }
}

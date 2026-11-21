package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.progressive.BackgroundNeighborLoader;
import com.aresstack.spaceharvester.application.query.progressive.NeighborLoader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Fehlende Nachbarn laden als Hintergrundauftrag „Nachbarseiten laden“; nur erfundene Seiten. */
class BackgroundNeighborLoaderTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final Set<PageKey> cache = ConcurrentHashMap.newKeySet();
    private final BackgroundJobsTest.Steps executor = new BackgroundJobsTest.Steps();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(4, 10, Duration.ofHours(1)),
            new BackgroundJobsTest.Ticking());
    private final AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> cache.add(key),
            new AcquisitionScheduler.Limits(2, 0, 2, 50));
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final BackgroundNeighborLoader loader = new BackgroundNeighborLoader(new PageLoads(background, scheduler,
            APP, cache::contains, key -> Optional.of("ALPHA")), background);

    @AfterEach
    void close() throws Exception {
        scheduler.close();
        jobs.close();
    }

    @Test
    void missingNeighborsLoadAsABackgroundJobAndTheEndIsReportedOnce() throws Exception {
        cache.add(new PageKey("wiki", "da"));
        List<Boolean> ends = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);

        NeighborLoader.Load load = loader.load(APP, List.of(new PageKey("wiki", "da"), new PageKey("wiki", "fehlt")))
                .orElseThrow();
        load.whenDone(cancelled -> {
            ends.add(cancelled);
            ended.countDown();
        });
        assertThat(ends).isEmpty();
        executor.runAll();
        assertThat(ended.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);

        assertThat(background.group(load.id()).orElseThrow().title()).isEqualTo(BackgroundNeighborLoader.TITLE);
        assertThat(cache).contains(new PageKey("wiki", "fehlt"));
        assertThat(ends).containsExactly(false);
    }

    @Test
    void nothingLoadsWhenEverythingIsStored() {
        cache.add(new PageKey("wiki", "da"));

        assertThat(loader.load(APP, List.of(new PageKey("wiki", "da")))).isEmpty();
        assertThat(background.groups()).isEmpty();
    }

    @Test
    void aSecondEqualSearchStartsNoGroupButHearsTheEndOfTheFirst() throws Exception {
        List<PageKey> missing = List.of(new PageKey("wiki", "fehlt"));
        NeighborLoader.Load first = loader.load(APP, missing).orElseThrow();

        NeighborLoader.Load second = loader.load(APP, missing).orElseThrow();
        assertThat(background.groups()).singleElement().satisfies(group ->
                assertThat(group.groupId()).isEqualTo(first.id()));
        assertThat(second.id()).isEqualTo(first.id());
        List<Boolean> ends = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
        second.whenDone(cancelled -> {
            ends.add(cancelled);
            ended.countDown();
        });
        assertThat(ends).isEmpty();

        executor.runAll();

        assertThat(ended.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(ends).containsExactly(false);
        assertThat(cache).containsAll(missing);
    }

    @Test
    void aPausedGroupStillCountsAsLoadingAndTheEndWaitsForIt() throws Exception {
        List<PageKey> missing = List.of(new PageKey("wiki", "fehlt"));
        NeighborLoader.Load first = loader.load(APP, missing).orElseThrow();
        background.pause(first.id());
        executor.runAll();

        NeighborLoader.Load second = loader.load(APP, missing).orElseThrow();
        List<Boolean> ends = new java.util.concurrent.CopyOnWriteArrayList<>();
        second.whenDone(ends::add);
        Thread.sleep(300);
        assertThat(background.groups()).hasSize(1);
        assertThat(ends).isEmpty();
    }
}

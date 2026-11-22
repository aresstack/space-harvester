package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SearchHitPrefetchTest {

    private static final Duration HANG_LIMIT = Duration.ofSeconds(120);
    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final List<PageKey> loaded = Collections.synchronizedList(new ArrayList<>());
    private final Map<PageKey, String> cache = Map.of(new PageKey("wiki", "1"), "3", new PageKey("wiki", "2"), "1");

    @Test
    void loadsEveryHitWhoseVersionIsNotCachedAndWaitsForIt() throws Exception {
        List<KeywordSearch.Hit> hits = new ArrayList<>(List.of(hit("1", "3"), hit("2", "2"), hit("3", null)));
        IntStream.range(10, 30).forEach(i -> hits.add(hit(String.valueOf(i), "1")));

        // Die Suche kehrt zurück, sobald alle Ladungen fertig sind; die Frist greift nur bei einem Hänger.
        try (SearchHitPrefetch prefetch = prefetch(HANG_LIMIT, null)) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, hits);
            assertThat(fill.pending()).isEmpty();
            assertThat(fill.failed()).isZero();
            assertThat(fill.progress().done()).isTrue();
            assertThat(prefetch.progress(fill.progressId())).contains(fill.progress());
        }

        // Nicht nur die ersten zehn: jede gefundene Seite des Stapels, die gleiche Version nicht noch einmal.
        assertThat(loaded).extracting(PageKey::pageId).hasSize(22).doesNotContain("1").contains("2", "3", "29");
    }

    @Test
    void whatIsStillLoadingIsCountedAndKeepsLoadingForTechnicalCallers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (SearchHitPrefetch prefetch = prefetch(Duration.ofMillis(100), release)) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, List.of(hit("5", "1"), hit("6", "1")));
            assertThat(fill.pending()).extracting(KeywordSearch.Hit::pageId).containsExactly("5", "6");
            assertThat(fill.failed()).isZero();

            release.countDown();
        }
        assertThat(loaded).extracting(PageKey::pageId).containsExactlyInAnyOrder("5", "6");
    }

    @Test
    void delegatedUsersLoadOnlyDuringTheirCall() throws Exception {
        AccessContext user = AccessContext.endUser("u", "app", new UserCredential("wiki", "t"));
        CountDownLatch release = new CountDownLatch(1);
        List<KeywordSearch.Hit> hits = IntStream.range(40, 50).mapToObj(i -> hit(String.valueOf(i), "1")).toList();

        try (SearchHitPrefetch prefetch = prefetch(Duration.ofMillis(100), release)) {
            assertThat(prefetch.prefetch(user, hits).pending()).hasSize(10);

            release.countDown();
            TimeUnit.MILLISECONDS.sleep(200);
        }
        // Nur die beiden schon laufenden Abrufe enden regulär, die wartenden entfallen mit dem Aufruf.
        assertThat(loaded).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void theProgressOfABatchArrivesWithoutAnyQueryEndingWithDone() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<KeywordSearch.Progress> mine = new LinkedBlockingQueue<>();
        BlockingQueue<KeywordSearch.Progress> other = new LinkedBlockingQueue<>();
        try (SearchHitPrefetch prefetch = prefetch(Duration.ofMillis(100), release)) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, List.of(hit("5", "1"), hit("6", "1")));
            // Wie die App: erst anmelden, dann einmal lesen.
            prefetch.subscribe(fill.progressId(), mine::add);
            prefetch.subscribe("anderer-stapel", other::add);
            assertThat(prefetch.progress(fill.progressId()).orElseThrow().done()).isFalse();

            release.countDown();
            KeywordSearch.Progress last = null;
            while (last == null || !last.done()) {
                last = mine.poll(10, TimeUnit.SECONDS);
                assertThat(last).as("Meldung").isNotNull();
            }
            assertThat(last.loaded()).isEqualTo(2);
            assertThat(last.id()).isEqualTo(fill.progressId());
            assertThat(other.poll(300, TimeUnit.MILLISECONDS)).as("fremder Stapel").isNull();
        }
    }

    @Test
    void aCancelArrivesAndAClosedSubscriptionGetsNothingMore() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<KeywordSearch.Progress> seen = new LinkedBlockingQueue<>();
        try (SearchHitPrefetch prefetch = prefetch(Duration.ofMillis(100), release)) {
            List<KeywordSearch.Hit> hits = IntStream.range(60, 66).mapToObj(i -> hit(String.valueOf(i), "1")).toList();
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, hits);
            Subscription subscription = prefetch.subscribe(fill.progressId(), seen::add);

            assertThat(prefetch.cancel(fill.progressId())).isTrue();
            KeywordSearch.Progress cancelled = seen.poll(10, TimeUnit.SECONDS);
            assertThat(cancelled).isNotNull();
            assertThat(cancelled.cancelled()).isTrue();

            subscription.close();
            seen.clear();
            release.countDown();
            assertThat(seen.poll(500, TimeUnit.MILLISECONDS)).as("abgemeldet").isNull();
        }
    }

    @Test
    void aDisabledPrefetchLoadsNothing() {
        assertThat(SearchHitPrefetch.disabled().prefetch(APP, List.of(hit("9", "1"))))
                .isEqualTo(KeywordSearch.Prefetch.Fill.NONE);
        assertThat(loaded).isEmpty();
    }

    @Test
    void aFailedLoadIsCountedAsAGapNotAsLoaded() throws Exception {
        try (SearchHitPrefetch prefetch = new SearchHitPrefetch(key -> Optional.empty(), (caller, key) -> {
            if (key.pageId().equals("7")) {
                throw new IllegalStateException("Quelle antwortet nicht");
            }
            loaded.add(key);
        }, SearchHitPrefetch.background(2, 50), HANG_LIMIT)) {
            KeywordSearch.Prefetch.Fill fill = prefetch.prefetch(APP, List.of(hit("7", "1"), hit("8", "1")));

            assertThat(fill.failed()).isEqualTo(1);
            assertThat(fill.pending()).isEmpty();
        }
        assertThat(loaded).extracting(PageKey::pageId).containsExactly("8");
    }

    /** Planer, Aufträge und „Seiten nachladen“ wie in der App, mit gehaltenem Ausführer der Aufträge. */
    private final class Visible implements AutoCloseable {

        final CountDownLatch release = new CountDownLatch(1);
        final java.util.Queue<Runnable> held = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler scheduler =
                new com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler((caller, key) -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    loaded.add(key);
                }, new com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler.Limits(1, 0, 1, 50));
        final com.aresstack.spaceharvester.application.jobs.Jobs jobs =
                new com.aresstack.spaceharvester.application.jobs.Jobs(
                        new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), held::add,
                        new com.aresstack.spaceharvester.application.jobs.JobLimits(4, 10, Duration.ofHours(1)),
                        java.time.Clock.systemUTC());
        final com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs, "app");
        final com.aresstack.spaceharvester.application.background.PageLoads loads =
                new com.aresstack.spaceharvester.application.background.PageLoads(background, scheduler, APP,
                        key -> false, key -> Optional.of("DEV"));
        final SearchHitPrefetch prefetch = new SearchHitPrefetch(key -> Optional.empty(), key -> Optional.empty(),
                scheduler, HANG_LIMIT, Duration.ZERO);

        Visible() {
            prefetch.pageLoads(loads);
        }

        void runAll() {
            Runnable next;
            while ((next = held.poll()) != null) {
                next.run();
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            prefetch.close();
            scheduler.close();
            jobs.close();
        }
    }

    @Test
    void hitsThatKeepLoadingAfterTheSearchReturnsAreAVisibleJobThatLoadsNothingTwice() throws Exception {
        // Fundstelle 13: Die Suche kehrt sofort zurück; was weiterlädt, steht als „Suchtreffer laden“ in der Liste.
        try (Visible visible = new Visible()) {
            KeywordSearch.Prefetch.Fill fill = visible.prefetch.prefetch(APP, List.of(hit("5", "1"), hit("6", "1")));
            assertThat(fill.pending()).extracting(KeywordSearch.Hit::pageId).containsExactly("5", "6");

            List<com.aresstack.spaceharvester.application.background.BackgroundGroup> groups =
                    visible.background.groups();
            assertThat(groups).singleElement().satisfies(group -> {
                assertThat(group.title()).isEqualTo(SearchHitPrefetch.TITLE);
                assertThat(group.parts()).singleElement().satisfies(part -> assertThat(part.parameter(
                        com.aresstack.spaceharvester.application.background.PageLoads.PAGES)).isEqualTo("5\n6"));
            });

            visible.release.countDown();
            visible.runAll();

            com.aresstack.spaceharvester.application.background.BackgroundGroup done =
                    visible.background.group(groups.getFirst().groupId()).orElseThrow();
            assertThat(done.completed()).isTrue();
            assertThat(done.written()).isEqualTo(2);
            // Der Auftrag wartet auf die Abrufe der Suche, statt neu anzufordern.
            assertThat(loaded).extracting(PageKey::pageId).containsExactlyInAnyOrder("5", "6");
            KeywordSearch.Progress progress = visible.prefetch.progress(fill.progressId()).orElseThrow();
            assertThat(progress.loaded()).isEqualTo(2);
        }
    }

    @Test
    void cancellingInTheHitListAlsoCancelsTheJob() throws Exception {
        try (Visible visible = new Visible()) {
            KeywordSearch.Prefetch.Fill fill = visible.prefetch.prefetch(APP, List.of(hit("5", "1"), hit("6", "1")));
            String groupId = visible.background.groups().getFirst().groupId();

            assertThat(visible.prefetch.cancel(fill.progressId())).isTrue();
            visible.runAll();

            assertThat(visible.background.group(groupId).orElseThrow().parts()).extracting(
                    com.aresstack.spaceharvester.application.jobs.JobStatus::state)
                    .containsOnly(com.aresstack.spaceharvester.application.jobs.JobState.CANCELLED);
        }
        // Höchstens der schon laufende Abruf endet regulär.
        assertThat(loaded).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void delegatedUsersStartNoJob() throws Exception {
        AccessContext user = AccessContext.endUser("u", "app", new UserCredential("wiki", "t"));
        try (Visible visible = new Visible()) {
            visible.prefetch.prefetch(user, List.of(hit("5", "1")));

            assertThat(visible.background.groups()).isEmpty();
        }
    }

    private SearchHitPrefetch prefetch(Duration wait, CountDownLatch release) {
        return new SearchHitPrefetch(key -> Optional.ofNullable(cache.get(key)), (caller, key) -> {
            if (release != null) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            loaded.add(key);
        }, SearchHitPrefetch.background(2, 50), wait);
    }

    private static KeywordSearch.Hit hit(String pageId, String version) {
        return new KeywordSearch.Hit("wiki", pageId, "DEV", "T", "", null, version, null, null);
    }
}

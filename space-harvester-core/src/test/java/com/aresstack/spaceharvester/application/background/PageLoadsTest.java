package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** „Seiten nachladen“ als Hintergrundauftrag, je Space ein Unterauftrag; nur erfundene Quellen und Seiten. */
class PageLoadsTest {

    private final Set<PageKey> cache = ConcurrentHashMap.newKeySet();
    private final Map<String, String> spaces = Map.of("1", "ALPHA", "2", "ALPHA", "3", "BETA", "4", "BETA");
    private final BackgroundJobsTest.Steps executor = new BackgroundJobsTest.Steps();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), executor, new JobLimits(4, 10, Duration.ofHours(1)),
            new BackgroundJobsTest.Ticking());
    private final AcquisitionScheduler scheduler = new AcquisitionScheduler((caller, key) -> {
        if (key.pageId().equals("4")) {
            throw new IllegalStateException("erfundener Fehler");
        }
        cache.add(key);
    }, new AcquisitionScheduler.Limits(2, 0, 2, 50));
    private final BackgroundJobs background = new BackgroundJobs(jobs, "app");
    private final PageLoads loads = new PageLoads(background, scheduler, AccessContext.technicalClient("app"),
            cache::contains, key -> Optional.ofNullable(spaces.get(key.pageId())));

    @AfterEach
    void close() throws Exception {
        scheduler.close();
        jobs.close();
    }

    private static List<PageKey> pages(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new PageKey("wiki", id)).toList();
    }

    @Test
    void missingPagesLoadInOneBackgroundJobPerSpace() {
        cache.add(new PageKey("wiki", "2"));

        BackgroundGroup group = loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                pages("1", "2", "3", "4")).orElseThrow();
        executor.runAll();

        assertThat(group.parts()).extracting(JobStatus::partitionKey).containsExactlyInAnyOrder("ALPHA", "BETA");
        BackgroundGroup done = background.group(group.groupId()).orElseThrow();
        assertThat(done.finished()).isTrue();
        assertThat(done.title()).isEqualTo("Nachbarseiten laden");
        assertThat(done.written()).isEqualTo(2);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(cache).contains(new PageKey("wiki", "1"), new PageKey("wiki", "3"));
        // Die gescheiterte Seite lässt ihren Unterauftrag scheitern, damit „Fortsetzen“ sie erneut versucht.
        JobStatus beta = done.parts().stream().filter(part -> "BETA".equals(part.partitionKey())).findFirst()
                .orElseThrow();
        assertThat(beta.state()).isEqualTo(JobState.FAILED);
        assertThat(beta.error().code()).isEqualTo(PageLoads.PAGES_MISSING);
        assertThat(beta.lifecycle().actions()).contains(com.aresstack.spaceharvester.application.jobs.JobAction.RESUME);
        assertThat(done.parts().stream().filter(part -> "ALPHA".equals(part.partitionKey())))
                .extracting(JobStatus::state).containsOnly(JobState.COMPLETED);
    }

    @Test
    void moreMissingPagesThanTheSchedulerHoldsStillLoadInWindows() throws Exception {
        java.util.List<PageKey> many = new java.util.ArrayList<>();
        java.util.Map<String, String> all = new java.util.HashMap<>();
        for (int i = 0; i < 120; i++) {
            many.add(new PageKey("wiki", "p" + i));
        }
        AcquisitionScheduler small = new AcquisitionScheduler((caller, key) -> cache.add(key),
                new AcquisitionScheduler.Limits(2, 0, 2, 10));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-small");
            PageLoads windowed = new PageLoads(own, small, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of("DELTA"));
            BackgroundGroup group = windowed.load(AccessContext.technicalClient("app"), "Viele Seiten", many)
                    .orElseThrow();
            executor.runAll();

            BackgroundGroup done = own.group(group.groupId()).orElseThrow();
            assertThat(done.completed()).isTrue();
            assertThat(done.written()).isEqualTo(120);
        } finally {
            small.close();
        }
    }

    @Test
    void nothingStartsWhenEveryPageIsCached() {
        cache.addAll(pages("1", "3"));

        assertThat(loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden", pages("1", "3")))
                .isEmpty();
        assertThat(background.groups()).isEmpty();
    }

    @Test
    void aResumedJobLoadsOnlyWhatIsStillMissing() {
        BackgroundGroup group = loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                pages("1", "2")).orElseThrow();
        background.pause(group.groupId());
        executor.runAll();
        cache.add(new PageKey("wiki", "1"));

        background.resume(group.groupId());
        executor.runAll();

        BackgroundGroup done = background.group(group.groupId()).orElseThrow();
        assertThat(done.completed()).isTrue();
        assertThat(done.written()).isEqualTo(1);
        assertThat(cache).contains(new PageKey("wiki", "2"));
    }

    @Test
    void theBackgroundNeverLoadsWithAUsersSignIn() {
        AccessContext delegated = AccessContext.endUser("erfunden", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));

        assertThatThrownBy(() -> loads.load(delegated, "x", pages("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingSignInOrALostConnectionPausesThePartInsteadOfFailingIt() throws Exception {
        AcquisitionScheduler failing = new AcquisitionScheduler((caller, key) -> {
            if (key.pageId().startsWith("s")) {
                throw new com.aresstack.spaceharvester.application.signin.SignInPendingException("wiki",
                        "anfrage-erfunden", null);
            }
            throw new com.aresstack.spaceharvester.application.sources.SourceAccessException("wiki",
                    com.aresstack.spaceharvester.application.sources.SourceAccessException.Reason.UNAVAILABLE,
                    "erfunden nicht erreichbar", new java.net.ConnectException("erfunden"));
        }, new AcquisitionScheduler.Limits(2, 0, 2, 50));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-failing");
            PageLoads paused = new PageLoads(own, failing, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of(key.pageId().startsWith("s") ? "SIGN" : "NET"));
            BackgroundGroup group = paused.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                    pages("s1", "s2", "n1")).orElseThrow();
            executor.runAll();

            BackgroundGroup done = own.group(group.groupId()).orElseThrow();
            JobStatus sign = done.parts().stream().filter(part -> "SIGN".equals(part.partitionKey())).findFirst()
                    .orElseThrow();
            JobStatus net = done.parts().stream().filter(part -> "NET".equals(part.partitionKey())).findFirst()
                    .orElseThrow();
            assertThat(sign.state()).isEqualTo(JobState.PAUSED);
            assertThat(sign.pauseReason())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.SIGN_IN_REQUIRED);
            assertThat(sign.lifecycle().key()).isEqualTo("PAUSED_SIGN_IN_REQUIRED");
            assertThat(net.state()).isEqualTo(JobState.PAUSED);
            assertThat(net.pauseReason())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.CONNECTION_LOST);
            assertThat(net.error()).isNull();
        } finally {
            failing.close();
        }
    }

    @Test
    void ticketsDroppedByTheShutdownCountAsInterruptedNotFailed() throws Exception {
        java.util.concurrent.CountDownLatch loading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AcquisitionScheduler slow = new AcquisitionScheduler((caller, key) -> {
            loading.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cache.add(key);
        }, new AcquisitionScheduler.Limits(1, 0, 1, 50));
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newCachedThreadPool();
        InMemoryJobStore store = new InMemoryJobStore();
        Jobs real = new Jobs(store, threads, new JobLimits(4, 10, Duration.ofHours(1)),
                java.time.Clock.systemUTC());
        try {
            BackgroundJobs own = new BackgroundJobs(real, "app-slow");
            PageLoads windowed = new PageLoads(own, slow, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of("ALPHA"));
            BackgroundGroup group = windowed.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                    pages("x1", "x2", "x3")).orElseThrow();
            assertThat(loading.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // Reihenfolge wie Harvester.close: erst unterbrochen markieren, dann wirft der Planer Wartendes ab.
            real.interruptBackground();
            Thread closing = new Thread(() -> {
                try {
                    slow.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            closing.start();
            String id = group.parts().getFirst().id();
            java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
            // Erst freigeben, wenn close() Wartendes abgeworfen hat und auf den laufenden Abruf wartet.
            while (closing.getState() != Thread.State.TIMED_WAITING && java.time.Instant.now().isBefore(deadline)) {
                Thread.sleep(5);
            }
            release.countDown();
            while (!real.status(id).state().terminal() && java.time.Instant.now().isBefore(deadline)) {
                Thread.sleep(20);
            }
            closing.join(10_000);
            JobStatus part = real.status(id);
            assertThat(part.state()).isEqualTo(JobState.FAILED);
            assertThat(part.error().code())
                    .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobLifecycleState.INTERRUPTED);
        } finally {
            release.countDown();
            real.close();
            threads.shutdownNow();
        }
    }

    @Test
    void waitingForAPageWakesOnlyOnItsEndOrAStopRequestNeverOnATimer() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        com.aresstack.spaceharvester.application.jobs.JobContext context =
                new com.aresstack.spaceharvester.application.jobs.JobContext() {
                    @Override
                    public String jobId() {
                        return "j";
                    }

                    @Override
                    public void checkpoint() {
                        checkpoints.incrementAndGet();
                    }

                    @Override
                    public boolean cancelRequested() {
                        return false;
                    }

                    @Override
                    public void discovered(long pages) {
                    }

                    @Override
                    public void processed(long pages) {
                    }

                    @Override
                    public void written(long pages) {
                    }

                    @Override
                    public void failed(long pages) {
                    }
                };
        java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
        PageLoads.Wake wake = new PageLoads.Wake();
        java.util.concurrent.atomic.AtomicReference<Boolean> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                result.set(PageLoads.await(context, done, wake));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
        while (waiter.getState() != Thread.State.WAITING && java.time.Instant.now().isBefore(deadline)) {
            Thread.sleep(5);
        }

        // Ohne Meldung: nichts weckt, auch nicht nach mehr als einer früheren Taktlänge.
        Thread.sleep(1_200);
        assertThat(checkpoints.get()).isEqualTo(1);

        // Eine Anforderung zum Anhalten weckt einmal und prüft.
        wake.ring();
        while (checkpoints.get() < 2 && java.time.Instant.now().isBefore(deadline)) {
            Thread.sleep(5);
        }
        assertThat(checkpoints.get()).isEqualTo(2);

        done.complete(true);
        waiter.join(10_000);
        assertThat(result.get()).isTrue();
        assertThat(checkpoints.get()).isEqualTo(3);
    }
}

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
    void adoptedTicketsAreAwaitedAndCountedInsteadOfRequestedAgain() throws Exception {
        // Fundstelle 13: Die Abrufe der Suche bleiben, wie sie sind; der Teil wartet auf sie.
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AcquisitionScheduler gated = new AcquisitionScheduler((caller, key) -> {
            calls.incrementAndGet();
            try {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cache.add(key);
        }, new AcquisitionScheduler.Limits(1, 0, 1, 50));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-adopt");
            PageLoads adopting = new PageLoads(own, gated, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of("ALPHA"));
            Map<PageKey, AcquisitionScheduler.Ticket> tickets = new java.util.LinkedHashMap<>();
            for (PageKey key : pages("1", "2")) {
                tickets.put(key, gated.request(AccessContext.technicalClient("app"), key,
                        AcquisitionScheduler.Priority.FOREGROUND));
            }

            BackgroundGroup group = adopting.adopt(AccessContext.technicalClient("app"), "Suchtreffer laden", tickets,
                    com.aresstack.spaceharvester.application.changelog.ChangeTrigger.SEARCH).started().orElseThrow();
            assertThat(group.parts()).singleElement().satisfies(part -> {
                assertThat(part.parameter(PageLoads.PAGES)).isEqualTo("1\n2");
                assertThat(part.parameter(PageLoads.REFRESH)).isEqualTo("true");
            });
            gate.countDown();
            executor.runAll();

            BackgroundGroup done = own.group(group.groupId()).orElseThrow();
            assertThat(done.completed()).isTrue();
            assertThat(done.written()).isEqualTo(2);
            assertThat(calls).hasValue(2);
        } finally {
            gate.countDown();
            gated.close();
        }
    }

    @Test
    void cancellingAWaitingAdoptedGroupWithdrawsItsTickets() throws Exception {
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        AcquisitionScheduler gated = new AcquisitionScheduler((caller, key) -> {
            try {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cache.add(key);
        }, new AcquisitionScheduler.Limits(1, 0, 1, 50));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-adopt");
            PageLoads adopting = new PageLoads(own, gated, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of("ALPHA"));
            Map<PageKey, AcquisitionScheduler.Ticket> tickets = new java.util.LinkedHashMap<>();
            for (PageKey key : pages("1", "2")) {
                tickets.put(key, gated.request(AccessContext.technicalClient("app"), key,
                        AcquisitionScheduler.Priority.FOREGROUND));
            }
            BackgroundGroup group = adopting.adopt(AccessContext.technicalClient("app"), "Suchtreffer laden", tickets,
                    com.aresstack.spaceharvester.application.changelog.ChangeTrigger.SEARCH).started().orElseThrow();

            adopting.cancel(group.groupId());
            // Der Teil hatte schon einen Platz: Er endet an seinem ersten sicheren Punkt, ohne einen Abruf zu nehmen.
            executor.runAll();

            // Die Auftragsmeldung kommt gebündelt auf eigenem Thread (ADR 0027).
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!tickets.values().stream().allMatch(ticket -> ticket.done().isDone())
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(tickets.values()).allSatisfy(ticket -> assertThat(ticket.done().isCancelled()).isTrue());
        } finally {
            gate.countDown();
            gated.close();
        }
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
    void aRefreshLoadsPagesThatAreAlreadyCached() throws Exception {
        // Fundstelle 10: Die Suche meldet eine gespeicherte Seite als veraltet.
        cache.add(new PageKey("wiki", "1"));
        java.util.concurrent.atomic.AtomicInteger loaded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger strict = new java.util.concurrent.atomic.AtomicInteger();
        // Hintergrund abgeschaltet: Der Abruf läuft im Vordergrund und muss trotzdem streng laden (Codex-Befund #1080).
        AcquisitionScheduler counting = new AcquisitionScheduler((caller, key, background) -> {
            loaded.incrementAndGet();
            if (background) {
                strict.incrementAndGet();
            }
        }, new AcquisitionScheduler.Limits(2, 0, 2, 50), null, Duration.ofSeconds(1));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-refresh");
            PageLoads refreshing = new PageLoads(own, counting, AccessContext.technicalClient("app"),
                    cache::contains, key -> Optional.of("ALPHA"));
            assertThat(refreshing.join(AccessContext.technicalClient("app"), "x", pages("1"), false).started())
                    .as("ohne refresh fällt die gespeicherte Seite heraus").isEmpty();

            BackgroundGroup group = refreshing.join(AccessContext.technicalClient("app"), "Veraltete Treffer nachladen",
                    pages("1"), true).started().orElseThrow();
            executor.runAll();

            assertThat(loaded).hasValue(1);
            assertThat(strict).as("nie aus dem Cache-Zeitfenster").hasValue(1);
            assertThat(own.group(group.groupId()).orElseThrow().parts()).singleElement().satisfies(part -> {
                assertThat(part.parameter(PageLoads.REFRESH)).isEqualTo("true");
                assertThat(part.state()).isEqualTo(JobState.COMPLETED);
            });
        } finally {
            counting.close();
        }
    }

    @Test
    void aUsersSignInLivesOnlyInMemoryAndAfterARestartThePartEndsYellow() {
        // Abnahme PR 4: auch Benutzer mit Anmeldung sichtbar; nach einem Neustart nie mit technischen Rechten weiter.
        AccessContext delegated = AccessContext.endUser("erfunden", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));
        BackgroundGroup group = loads.join(delegated, "Veraltete Treffer nachladen", pages("1"), true).started()
                .orElseThrow();
        JobStatus part = group.parts().getFirst();
        assertThat(part.parameter(PageLoads.CALLER)).isNotBlank();
        assertThat(part.parameter(PageLoads.OWNER)).isEqualTo(delegated.jobOwner());
        background.pause(group.groupId());
        executor.runAll();

        // Neustart: eine neue Laufzeit kennt den Aufrufer nicht.
        new PageLoads(background, scheduler, AccessContext.technicalClient("app"), cache::contains,
                key -> Optional.ofNullable(spaces.get(key.pageId())));
        background.resume(group.groupId());
        executor.runAll();

        JobStatus ended = background.group(group.groupId()).orElseThrow().parts().getFirst();
        assertThat(ended.state()).isEqualTo(JobState.COMPLETED);
        assertThat(ended.incomplete()).isTrue();
        assertThat(jobs.events(ended.id())).anySatisfy(event -> assertThat(event.code())
                .isEqualTo(PageLoads.NOT_RESUMED));
        assertThat(cache).as("nichts mit fremden Rechten geladen").doesNotContain(new PageKey("wiki", "1"));
    }

    @Test
    void aDelegatedUserReloadsWithTheirOwnRights() throws Exception {
        // Abnahme #1080: der erfolgreiche Weg eines Benutzers mit Anmeldung, mit seinen Rechten.
        AccessContext delegated = AccessContext.endUser("erfunden", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));
        List<AccessContext> used = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AcquisitionScheduler recording = new AcquisitionScheduler((caller, key) -> {
            used.add(caller);
            cache.add(key);
        }, new AcquisitionScheduler.Limits(2, 1, 2, 50));
        try {
            BackgroundJobs own = new BackgroundJobs(jobs, "app-delegated");
            PageLoads delegatedLoads = new PageLoads(own, recording, AccessContext.technicalClient("app"),
                    cache::contains, key -> Optional.of("ALPHA"));
            BackgroundGroup group = delegatedLoads.join(delegated, "Veraltete Treffer nachladen", pages("1", "2"), true)
                    .started().orElseThrow();
            executor.runAll();

            assertThat(used).hasSize(2).containsOnly(delegated);
            assertThat(own.group(group.groupId()).orElseThrow().parts()).singleElement().satisfies(part -> {
                assertThat(part.state()).isEqualTo(JobState.COMPLETED);
                assertThat(part.incomplete()).isFalse();
            });
        } finally {
            recording.close();
        }
    }

    @Test
    void aRefreshDoesNotJoinAPausedRefreshPartThatWouldNeverLoad() {
        // Abnahme #1080: etwa nach abgelaufener Anmeldung angehalten; die veralteten Seiten kämen nie.
        BackgroundGroup paused = loads.join(AccessContext.technicalClient("app"), "Veraltete Treffer nachladen",
                pages("1"), true).started().orElseThrow();
        background.pause(paused.groupId());
        executor.runAll();
        assertThat(background.group(paused.groupId()).orElseThrow().parts()).extracting(JobStatus::state)
                .containsOnly(JobState.PAUSED);

        PageLoads.Joined again = loads.join(AccessContext.technicalClient("app"), "Veraltete Treffer nachladen",
                pages("1"), true);

        assertThat(again.joined()).isEmpty();
        assertThat(again.started()).isPresent();
    }

    @Test
    void theCallerOfAnOpenPartStaysHowEverManyGroupsFollowAndGoesWhenItEnds() throws Exception {
        // Abnahme #1080: kein falsches „nicht fortgesetzt“ ohne Neustart, weil ein Aufrufer aus einer Zuordnung fiel.
        AccessContext delegated = AccessContext.endUser("erfunden", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));
        Jobs many = new Jobs(new InMemoryJobStore(), executor, new JobLimits(4, 1000, Duration.ofHours(1)),
                new BackgroundJobsTest.Ticking());
        AcquisitionScheduler quick = new AcquisitionScheduler((caller, key) -> cache.add(key),
                new AcquisitionScheduler.Limits(4, 1, 4, 500), null, Duration.ZERO);
        try {
            BackgroundJobs own = new BackgroundJobs(many, "app-many");
            PageLoads held = new PageLoads(own, quick, AccessContext.technicalClient("app"), cache::contains,
                    key -> Optional.of("ALPHA"));
            BackgroundGroup first = held.join(delegated, "Veraltete Treffer nachladen", pages("u1"), true).started()
                    .orElseThrow();
            own.pause(first.groupId());
            executor.runAll();
            for (int i = 0; i < 300; i++) {
                held.join(AccessContext.technicalClient("app"), "Veraltete Treffer nachladen", pages("t" + i), true);
            }

            own.resume(first.groupId());
            executor.runAll();

            JobStatus ended = own.group(first.groupId()).orElseThrow().parts().getFirst();
            assertThat(ended.state()).isEqualTo(JobState.COMPLETED);
            assertThat(ended.incomplete()).isFalse();
            assertThat(many.events(ended.id())).noneSatisfy(event -> assertThat(event.code())
                    .isEqualTo(PageLoads.NOT_RESUMED));
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (held.heldCallers() > 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(held.heldCallers()).as("nach dem Ende aller Gruppen losgelassen").isZero();
        } finally {
            quick.close();
            many.close();
        }
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
    void pagesThatAnOpenPartAlreadyLoadsStartNoSecondGroup() {
        BackgroundGroup first = loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                pages("1", "3")).orElseThrow();

        // Dieselbe Suche noch einmal, bevor der erste Auftrag lief: nichts Neues.
        assertThat(loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden", pages("1", "3")))
                .isEmpty();
        // Teilweise neu: nur die neue Seite bekommt einen Auftrag.
        BackgroundGroup second = loads.load(AccessContext.technicalClient("app"), "Nachbarseiten laden",
                pages("1", "2")).orElseThrow();
        assertThat(second.parts()).singleElement().satisfies(part ->
                assertThat(part.parameter(PageLoads.PAGES)).isEqualTo("2"));
        assertThat(background.groups()).extracting(BackgroundGroup::groupId)
                .containsExactlyInAnyOrder(first.groupId(), second.groupId());
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

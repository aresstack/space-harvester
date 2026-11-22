package com.aresstack.spaceharvester.application.sources;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Löschen einer entfernten Quelle als Auftrag, über einen Neustart hinweg (Fundstelle 22); erfundene Quellen. */
class SourceWithdrawalTest {

    private final InMemoryJobStore store = new InMemoryJobStore();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> cleared = ConcurrentHashMap.newKeySet();

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    private Jobs jobs() {
        return new Jobs(store, executor, new JobLimits(2, 10, Duration.ofHours(1)), Clock.systemUTC());
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Bedingung nach 30 s nicht erfüllt").isLessThan(deadline);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    @Test
    void aRemovalSurvivesTheEndOfTheServiceAndIsResumedOnTheNextStart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Jobs before = jobs();
        SourceDataPurge first = new SourceDataPurge(before, List.of(
                new SourceDataPurge.Store("Seiten", id -> {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }),
                new SourceDataPurge.Store("Analysen", cleared::add)), id -> false);

        JobStatus started = first.startWithdrawal("altwiki", "app", SourceDataPurge.REMOVED);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(first.withdrawing("altwiki")).as("dieselbe ID bleibt gesperrt").isPresent();

        // Die App lädt neu: Der Dienst endet, das Löschen bricht nicht ab, sondern gilt als unterbrochen.
        before.close();
        release.countDown();
        eventually(() -> store.find(started.id()).orElseThrow().state().terminal());
        assertThat(store.find(started.id()).orElseThrow().state()).isEqualTo(JobState.FAILED);
        assertThat(cleared).isEmpty();

        Jobs after = jobs();
        try {
            SourceDataPurge second = new SourceDataPurge(after, List.of(
                    new SourceDataPurge.Store("Seiten", id -> { }),
                    new SourceDataPurge.Store("Analysen", cleared::add)), id -> false);
            assertThat(second.withdrawing("altwiki")).as("auch nach dem Neustart gesperrt").isPresent();

            List<JobStatus> resumed = second.resumeInterrupted();
            assertThat(resumed).singleElement().satisfies(job -> {
                assertThat(job.sourceId()).isEqualTo("altwiki");
                assertThat(job.parameter(SourceDataPurge.REASON)).isEqualTo(SourceDataPurge.REMOVED);
            });

            assertThat(after.await(resumed.getFirst().id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");
            assertThat(second.withdrawing("altwiki")).as("danach wieder frei").isEmpty();
        } finally {
            after.close();
        }
    }

    @Test
    void aRemovedSourceThatIsConfiguredAgainIsLeftAlone() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Jobs before = jobs();
        SourceDataPurge first = new SourceDataPurge(before, List.of(new SourceDataPurge.Store("Seiten", id -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), new SourceDataPurge.Store("Analysen", cleared::add)), id -> false);
        JobStatus started = first.startWithdrawal("altwiki", "app", SourceDataPurge.REMOVED);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        before.close();
        release.countDown();
        eventually(() -> store.find(started.id()).orElseThrow().state().terminal());

        Jobs after = jobs();
        try {
            SourceDataPurge second = new SourceDataPurge(after, List.of(
                    new SourceDataPurge.Store("Analysen", cleared::add)), id -> true);
            assertThat(second.resumeInterrupted()).isEmpty();
            assertThat(cleared).isEmpty();
        } finally {
            after.close();
        }
    }
}

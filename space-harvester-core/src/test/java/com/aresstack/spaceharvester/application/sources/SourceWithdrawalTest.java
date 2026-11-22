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

            List<JobStatus> resumed = second.resumePending();
            assertThat(resumed).singleElement().satisfies(job -> {
                // Im selben Eintrag: kein zweiter, der später wieder aufleben könnte (Abnahme Punkt 4).
                assertThat(job.id()).isEqualTo(started.id());
                assertThat(job.parameter(SourceDataPurge.REASON)).isEqualTo(SourceDataPurge.REMOVED);
            });

            assertThat(after.await(started.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");
            eventually(() -> second.withdrawing("altwiki").isEmpty());
        } finally {
            after.close();
        }
    }

    @Test
    void aRemovedSourceThatIsConfiguredAgainIsStillWithdrawnFirst() throws Exception {
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
            // Halb gelöschte Daten übernimmt die wieder eingerichtete Quelle nie: erst zu Ende löschen (Punkt 5).
            assertThat(second.resumePending()).extracting(JobStatus::id).containsExactly(started.id());
            assertThat(after.await(started.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");
        } finally {
            after.close();
        }
    }

    @Test
    void anInterruptedRemovalOutlivesTheRetentionOfFinishedJobs() throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-11-01T08:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return now.get();
            }
        };
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Jobs before = new Jobs(store, executor, new JobLimits(2, 10, Duration.ofHours(1)), clock);
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

        // Der Dienst ruht länger als die Aufbewahrungsfrist: Der unterbrochene Auftrag darf nicht ablaufen.
        now.set(now.get().plus(Duration.ofDays(30)));
        Jobs after = new Jobs(store, executor, new JobLimits(2, 10, Duration.ofHours(1)), clock);
        try {
            assertThat(after.purgeExpired()).isZero();
            SourceDataPurge second = new SourceDataPurge(after, List.of(
                    new SourceDataPurge.Store("Analysen", cleared::add)), id -> false);
            assertThat(second.withdrawing("altwiki")).isPresent();
            second.resumePending();
            assertThat(after.await(started.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");

            // Gelungen läuft er wie jeder andere ab.
            now.set(now.get().plus(Duration.ofDays(30)));
            eventually(() -> {
                after.purgeExpired();
                return store.find(started.id()).isEmpty();
            });
        } finally {
            after.close();
        }
    }

    @Test
    void aJobOfAMovedSourceWaitsBehindTheWithdrawalInsteadOfBeingCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Jobs jobs = jobs();
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Seiten", id -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }), new SourceDataPurge.Store("Analysen", cleared::add)), id -> true);
            JobStatus withdrawal = purge.startWithdrawal("altwiki", "app", SourceDataPurge.MOVED);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            // Ein Abgleich gleich nach dem Umstellen: wartet hinter dem Löschen (Jobs.sourceHold).
            JobStatus sync = submitSync(jobs);
            assertHeld(jobs, sync);

            release.countDown();
            assertThat(jobs.await(withdrawal.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(jobs.await(sync.id(), "app", Duration.ofSeconds(30)).state()).isEqualTo(JobState.COMPLETED);
        } finally {
            jobs.close();
        }
    }

    private static JobStatus submitSync(Jobs jobs) {
        return jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "altwiki", null, "app",
                com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> { }));
    }

    /** Der Auftrag wartet hinter dem offenen Löschen und sagt es. */
    private static void assertHeld(Jobs jobs, JobStatus job) throws InterruptedException {
        eventually(() -> jobs.events(job.id(), "app").stream()
                .anyMatch(event -> Jobs.WAITING_FOR_WITHDRAWAL.equals(event.code())));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void pausingAndResumingTheWithdrawalKeepsTheWaitingSyncHeldAlsoAfterARestart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Jobs before = jobs();
        SourceDataPurge first = new SourceDataPurge(before, List.of(new SourceDataPurge.Store("Seiten", id -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), new SourceDataPurge.Store("Analysen", cleared::add)), id -> true);
        JobStatus withdrawal = first.startWithdrawal("altwiki", "app", SourceDataPurge.MOVED);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        JobStatus sync = submitSync(before);
        before.pause(withdrawal.id(), "app");
        release.countDown();
        eventually(() -> before.status(withdrawal.id()).state() == JobState.PAUSED);
        // Angehalten ist das Löschen offen: Der Abgleich bleibt stehen.
        assertHeld(before, sync);
        assertThat(first.openWithdrawal("altwiki")).isEqualTo(withdrawal.id());
        before.close();

        // Nach dem Neustart hält das angehaltene Löschen weiter, auch einen neuen Abgleich.
        Jobs after = jobs();
        try {
            SourceDataPurge second = new SourceDataPurge(after, List.of(
                    new SourceDataPurge.Store("Analysen", cleared::add)), id -> true);
            assertThat(second.resumePending()).as("angehalten bleibt angehalten").isEmpty();
            JobStatus again = submitSync(after);
            assertHeld(after, again);

            second.resume(withdrawal.id(), "app");
            assertThat(after.await(withdrawal.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(after.await(again.id(), "app", Duration.ofSeconds(30)).state()).isEqualTo(JobState.COMPLETED);
        } finally {
            after.close();
        }
    }

    @Test
    void aWaitingSyncStaysHeldWhileAFailedWithdrawalWaitsForItsNextAttempt() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        Jobs jobs = jobs();
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Analysen", id -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Ablage gesperrt");
                }
                cleared.add(id);
            })), id -> true);
            purge.healDelays(List.of(Duration.ofMillis(800)));
            JobStatus withdrawal = purge.startWithdrawal("altwiki", "app", SourceDataPurge.MOVED);
            eventually(() -> jobs.status(withdrawal.id()).state() == JobState.FAILED);
            JobStatus sync = submitSync(jobs);
            assertHeld(jobs, sync);

            assertThat(jobs.await(sync.id(), "app", Duration.ofSeconds(30)).state()).isEqualTo(JobState.COMPLETED);
            assertThat(jobs.status(withdrawal.id()).state()).isEqualTo(JobState.COMPLETED);
            assertThat(attempts.get()).isEqualTo(2);
        } finally {
            jobs.close();
        }
    }

    @Test
    void theSelfHealingIsAcceptedEvenWithAFullQueue() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch blockers = new CountDownLatch(1);
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 1, Duration.ofHours(1)), Clock.systemUTC());
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Analysen", id -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Ablage gesperrt");
                }
                cleared.add(id);
            })), id -> false);
            purge.healDelays(List.of(Duration.ofMillis(300), Duration.ofMillis(50)));
            JobStatus withdrawal = purge.startWithdrawal("altwiki", "app", SourceDataPurge.REMOVED);
            eventually(() -> jobs.status(withdrawal.id()).state() == JobState.FAILED);
            // Die Warteschlange ist voll: Der nächste Versuch wird trotzdem angenommen (angenommene Arbeit, Abnahme
            // #1090) und wartet auf einen Platz, statt abgelehnt zu werden.
            for (int i = 0; i < 2; i++) {
                jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "andere", null, "app",
                        com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context ->
                                blockers.await(10, TimeUnit.SECONDS)));
            }
            eventually(() -> jobs.status(withdrawal.id()).state() == JobState.QUEUED);
            assertThat(purge.withdrawing("altwiki")).isPresent();

            blockers.countDown();
            eventually(() -> jobs.status(withdrawal.id()).state() == JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");
        } finally {
            blockers.countDown();
            jobs.close();
        }
    }

    @Test
    void aFullQueueNeverBlocksTheWithdrawalItsHeldJobsWaitFor() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch blockers = new CountDownLatch(1);
        // Ein Platz, eine Warteschlange von eins: Ohne Ausnahme füllten die gehaltenen Abgleiche alles.
        Jobs jobs = new Jobs(store, executor, new JobLimits(1, 1, Duration.ofHours(1)), Clock.systemUTC());
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Analysen", id -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Ablage gesperrt");
                }
                cleared.add(id);
            })), id -> true);
            purge.healDelays(List.of(Duration.ofMinutes(10)));
            JobStatus withdrawal = purge.startWithdrawal("altwiki", "app", SourceDataPurge.MOVED);
            eventually(() -> jobs.status(withdrawal.id()).state() == JobState.FAILED);

            // Gehaltene Abgleiche belegen keinen Platz in der Warteschlange.
            List<JobStatus> held = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                held.add(submitSync(jobs));
            }
            // Andere Aufträge füllen Platz und Warteschlange.
            for (int i = 0; i < 2; i++) {
                jobs.submit(com.aresstack.spaceharvester.application.jobs.JobType.SYNC, "andere", null, "app",
                        com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context ->
                                blockers.await(10, TimeUnit.SECONDS)));
            }
            // Fortsetzen des offenen Löschens ist angenommene Arbeit und umgeht die Grenze.
            purge.resume(withdrawal.id(), "app");
            blockers.countDown();

            assertThat(jobs.await(withdrawal.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            for (JobStatus sync : held) {
                assertThat(jobs.await(sync.id(), "app", Duration.ofSeconds(30)).state())
                        .isEqualTo(JobState.COMPLETED);
            }
        } finally {
            blockers.countDown();
            jobs.close();
        }
    }

    @Test
    void cancellingTheJobsOfTheSourceKeepsItsOpenWithdrawal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Jobs jobs = jobs();
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Seiten", id -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }), new SourceDataPurge.Store("Analysen", cleared::add)), id -> true);
            JobStatus withdrawal = purge.startWithdrawal("altwiki", "app", SourceDataPurge.MOVED);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            JobStatus sync = submitSync(jobs);

            // Etwa beim Abschalten der Quelle: Ihre Aufträge enden, ihr offenes Löschen läuft weiter.
            assertThat(jobs.cancelSource("altwiki", Duration.ofSeconds(5))).isTrue();
            assertThat(jobs.status(sync.id()).state()).isEqualTo(JobState.CANCELLED);
            assertThat(jobs.status(withdrawal.id()).state()).isEqualTo(JobState.RUNNING);

            release.countDown();
            assertThat(jobs.await(withdrawal.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");
        } finally {
            release.countDown();
            jobs.close();
        }
    }

    @Test
    void anOpenWithdrawalCannotBeSweptAway() throws Exception {
        Jobs jobs = jobs();
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Analysen", id -> {
                throw new IllegalStateException("Ablage gesperrt");
            })), id -> false);
            purge.healDelays(List.of(Duration.ofMinutes(10)));
            JobStatus withdrawal = purge.startWithdrawal("altwiki", "app", SourceDataPurge.REMOVED);
            eventually(() -> jobs.status(withdrawal.id()).state() == JobState.FAILED);

            // Der Besen lässt das gescheiterte, sich wiederholende Löschen stehen; Löschen von Hand ebenso.
            jobs.deleteFinished("app", null);
            assertThat(store.find(withdrawal.id())).isPresent();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jobs.delete(withdrawal.id(), "app"))
                    .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobStillRunningException.class);
            assertThat(purge.withdrawing("altwiki")).isPresent();

            // Bricht der Benutzer es ab, ist es nicht mehr offen und lässt sich löschen.
            jobs.retire(withdrawal.id(), "app", "Test");
            eventually(() -> purge.openWithdrawal("altwiki") == null);
            jobs.delete(withdrawal.id(), "app");
        } finally {
            jobs.close();
        }
    }

    @Test
    void aFailedRemovalHealsItselfAndKeepsTheIdLockedUntilItSucceeds() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch retry = new CountDownLatch(1);
        Jobs jobs = jobs();
        try {
            SourceDataPurge purge = new SourceDataPurge(jobs, List.of(new SourceDataPurge.Store("Analysen", id -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Ablage gesperrt");
                }
                try {
                    retry.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cleared.add(id);
            })), id -> false);
            purge.healDelays(List.of(Duration.ofMillis(50)));
            JobStatus started = purge.startWithdrawal("altwiki", "app", SourceDataPurge.REMOVED);

            // Gescheitert hebt die Sperre nicht auf; der zweite Versuch läuft von selbst im selben Eintrag.
            eventually(() -> attempts.get() == 2);
            assertThat(purge.withdrawing("altwiki")).isPresent();
            assertThat(jobs.status(started.id()).state()).isEqualTo(JobState.RUNNING);

            retry.countDown();
            assertThat(jobs.await(started.id(), "app", Duration.ofSeconds(30)).state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(cleared).containsExactly("altwiki");
            eventually(() -> purge.withdrawing("altwiki").isEmpty());
        } finally {
            jobs.close();
        }
    }

    @Test
    void aRemovalTheUserCancelledStaysCancelledAfterARestart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
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
        before.cancel(started.id());
        // Der Dienst endet, bevor der Abbruch ankommt (etwa ein Absturz): Der Wunsch des Benutzers bleibt.
        before.close();

        Jobs after = jobs();
        try {
            assertThat(store.find(started.id()).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
            SourceDataPurge second = new SourceDataPurge(after, List.of(
                    new SourceDataPurge.Store("Analysen", cleared::add)), id -> false);
            assertThat(second.resumePending()).isEmpty();
            assertThat(second.withdrawing("altwiki")).isEmpty();
        } finally {
            release.countDown();
            after.close();
        }
        assertThat(cleared).isEmpty();
    }
}

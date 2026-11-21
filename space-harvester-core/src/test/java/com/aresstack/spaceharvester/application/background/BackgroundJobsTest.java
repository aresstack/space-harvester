package com.aresstack.spaceharvester.application.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Hintergrundaufträge: Gruppe mit Unterauftrag je Space, Anhalten, Fortsetzen, Neustart (Angelo 21.11.2026). */
class BackgroundJobsTest {

    private static final String OWNER = "app";

    private final InMemoryJobStore store = new InMemoryJobStore();
    private final Steps executor = new Steps();
    private final Clock clock = new Ticking();
    private final List<BackgroundPart> seen = new CopyOnWriteArrayList<>();

    private Jobs jobs(Executor on) {
        return new Jobs(store, on, new JobLimits(4, 10, Duration.ofHours(1)), clock);
    }

    private BackgroundJobs background(Jobs jobs, AtomicBoolean autoResume) {
        BackgroundJobs background = new BackgroundJobs(jobs, OWNER);
        background.register(new BackgroundKind("demo", part -> JobTask.blocking(context -> {
            context.checkpoint();
            seen.add(part);
            context.processed(1);
            context.written(1);
        }), autoResume::get));
        return background;
    }

    private static List<BackgroundPart.Draft> twoSpaces() {
        return List.of(new BackgroundPart.Draft("wiki", "ALPHA", Map.of("x", "1")),
                new BackgroundPart.Draft("wiki", "BETA", Map.of("x", "2")));
    }

    @Test
    void aGroupHasOneBackgroundJobPerSpaceAndEachBuildsItsWorkFromItsParameters() {
        BackgroundJobs background = background(jobs(executor), new AtomicBoolean(true));

        BackgroundGroup group = background.start("demo", "Demo laden", twoSpaces());
        executor.runAll();

        assertThat(group.parts()).hasSize(2).allSatisfy(part -> {
            assertThat(part.type()).isEqualTo(JobType.BACKGROUND);
            assertThat(part.owner()).isEqualTo(OWNER);
            assertThat(part.parameter(BackgroundJobs.GROUP)).isEqualTo(group.groupId());
            assertThat(part.parameter(BackgroundJobs.TITLE)).isEqualTo("Demo laden");
            assertThat(part.parameter(BackgroundJobs.KIND)).isEqualTo("demo");
        });
        assertThat(seen).extracting(BackgroundPart::partitionKey).containsExactlyInAnyOrder("ALPHA", "BETA");
        assertThat(seen).allSatisfy(part -> {
            assertThat(part.groupId()).isEqualTo(group.groupId());
            assertThat(part.resumed()).isFalse();
            assertThat(part.parameters()).containsOnlyKeys("x");
        });
        BackgroundGroup done = background.group(group.groupId()).orElseThrow();
        assertThat(done.finished()).isTrue();
        assertThat(done.completed()).isTrue();
        assertThat(done.written()).isEqualTo(2);
        assertThat(background.groups()).extracting(BackgroundGroup::groupId).containsExactly(group.groupId());
    }

    @Test
    void reservedKeysAndUnknownKindsAreRejected() {
        BackgroundJobs background = background(jobs(executor), new AtomicBoolean(true));

        assertThatThrownBy(() -> new BackgroundPart.Draft("wiki", "ALPHA", Map.of(BackgroundJobs.GROUP, "g")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> background.start("unknown", "x", twoSpaces()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runningFindsAWaitingPartOfTheSameKindSourceAndSpace() {
        BackgroundJobs background = background(jobs(executor), new AtomicBoolean(true));
        background.start("demo", "Demo", twoSpaces());

        assertThat(background.running("demo", "wiki", "ALPHA")).isPresent();
        assertThat(background.running("demo", "wiki", "GAMMA")).isEmpty();

        executor.runAll();
        assertThat(background.running("demo", "wiki", "ALPHA")).isEmpty();
    }

    @Test
    void aPausedGroupResumesInTheSameEntriesWithFreshWork() {
        BackgroundJobs background = background(jobs(executor), new AtomicBoolean(true));
        BackgroundGroup group = background.start("demo", "Demo", twoSpaces());

        BackgroundGroup paused = background.pause(group.groupId()).orElseThrow();
        executor.runAll();
        assertThat(background.group(group.groupId()).orElseThrow().paused()).isTrue();
        assertThat(paused.jobIds()).containsExactlyElementsOf(group.jobIds());

        background.resume(group.groupId());
        executor.runAll();

        BackgroundGroup done = background.group(group.groupId()).orElseThrow();
        assertThat(done.completed()).isTrue();
        assertThat(done.jobIds()).containsExactlyInAnyOrderElementsOf(group.jobIds());
        assertThat(seen).isNotEmpty().allSatisfy(part -> assertThat(part.resumed()).isTrue());
    }

    @Test
    void aCancelledGroupStops() {
        BackgroundJobs background = background(jobs(executor), new AtomicBoolean(true));
        BackgroundGroup group = background.start("demo", "Demo", twoSpaces());

        background.cancel(group.groupId());
        executor.runAll();

        assertThat(background.group(group.groupId()).orElseThrow().parts()).extracting(JobStatus::state)
                .containsOnly(JobState.CANCELLED);
        assertThat(seen).isEmpty();
    }

    @Test
    void afterARestartInterruptedPartsResumeByThemselvesButPausedOnesStayPaused() {
        // Erster Dienst mit einem Platz für den Hintergrund: der erste Teil läuft (seine Arbeit endet nie), der zweite
        // wartet und wird angehalten.
        Jobs first = new Jobs(store, new Steps(), new JobLimits(2, 10, Duration.ofHours(1)), clock);
        BackgroundJobs before = background(first, new AtomicBoolean(true));
        BackgroundGroup group = before.start("demo", "Demo", twoSpaces());
        String pausedId = group.parts().get(1).id();
        first.pause(pausedId, OWNER);

        Jobs restarted = jobs(executor);
        BackgroundJobs after = background(restarted, new AtomicBoolean(true));
        List<JobStatus> resumed = after.resumeInterrupted();
        executor.runAll();

        String interruptedId = group.parts().get(0).id();
        assertThat(resumed).extracting(JobStatus::id).containsExactly(interruptedId);
        assertThat(restarted.status(interruptedId).state()).isEqualTo(JobState.COMPLETED);
        assertThat(restarted.status(pausedId).state()).isEqualTo(JobState.PAUSED);
        assertThat(seen).singleElement().satisfies(part -> {
            assertThat(part.jobId()).isEqualTo(interruptedId);
            assertThat(part.resumed()).isTrue();
            assertThat(part.parameter("x")).isEqualTo("1");
        });
    }

    @Test
    void afterARestartAKindThatSaysNoStaysInterrupted() {
        Jobs first = jobs(new Steps());
        BackgroundGroup group = background(first, new AtomicBoolean(true)).start("demo", "Demo", twoSpaces());

        Jobs restarted = jobs(executor);
        BackgroundJobs after = background(restarted, new AtomicBoolean(false));

        assertThat(after.resumeInterrupted()).isEmpty();
        assertThat(restarted.status(group.parts().get(0).id()).state()).isEqualTo(JobState.FAILED);
        // Von Hand fortsetzen geht trotzdem.
        after.resumeJob(group.parts().get(0).id());
        executor.runAll();
        assertThat(restarted.status(group.parts().get(0).id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void watchersLearnEveryChangeAndTheEndWithoutAsking() throws Exception {
        Jobs jobs = jobs(executor);
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        BackgroundGroup group = background.start("demo", "Demo", twoSpaces());
        List<BackgroundGroup> reports = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

        try (var subscription = background.watch(group.groupId(), report -> {
            reports.add(report);
            if (report.finished()) {
                finished.countDown();
            }
        })) {
            executor.runAll();
            assertThat(finished.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(reports).last().satisfies(report -> assertThat(report.completed()).isTrue());
    }

    @Test
    void whenFinishedTellsOnceAtTheEndAndAtOnceForAFinishedGroup() throws Exception {
        Jobs jobs = jobs(executor);
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        BackgroundGroup group = background.start("demo", "Demo", twoSpaces());
        List<BackgroundGroup> told = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

        background.whenFinished(group.groupId(), report -> {
            told.add(report);
            finished.countDown();
        });
        assertThat(told).isEmpty();
        executor.runAll();

        assertThat(finished.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(told).singleElement().satisfies(report -> assertThat(report.completed()).isTrue());

        List<BackgroundGroup> late = new CopyOnWriteArrayList<>();
        background.whenFinished(group.groupId(), late::add);
        assertThat(late).singleElement().satisfies(report -> assertThat(report.finished()).isTrue());
    }

    /** Führt Arbeit erst auf Anforderung aus. */
    static final class Steps implements Executor {
        private final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            pending.addLast(command);
        }

        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = pending.pollFirst();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }

    /** Jede Abfrage eine Millisekunde später, damit die Reihenfolge eindeutig ist. */
    static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-11-21T18:00:00Z");

        @Override
        public synchronized Instant instant() {
            now = now.plusMillis(1);
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

}

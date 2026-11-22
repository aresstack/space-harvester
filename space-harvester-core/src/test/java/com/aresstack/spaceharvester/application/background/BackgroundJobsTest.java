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

    /** Mit der Art „solo“: höchstens ein offener Teil je Quelle und Space (Abnahme #1052). */
    private BackgroundJobs exclusive(Jobs jobs) {
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        background.register(BackgroundKind.exclusive("solo", part -> JobTask.blocking(context -> {
            context.checkpoint();
            seen.add(part);
        }), () -> true));
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
    void startUnlessWaitingLeavesAWaitingPartAloneButNotARunningOne() {
        // Ein einziger Platz: Hintergrund startet nie, der Teil wartet.
        BackgroundJobs waitingOnly = background(new Jobs(new InMemoryJobStore(), executor,
                new JobLimits(1, 10, Duration.ofHours(1)), clock), new AtomicBoolean(true));
        BackgroundPart.Draft alpha = new BackgroundPart.Draft("wiki", "ALPHA");

        assertThat(waitingOnly.startUnlessWaiting("demo", "Demo", alpha)).isPresent();
        assertThat(waitingOnly.waiting("demo", "wiki", "ALPHA")).isPresent();
        assertThat(waitingOnly.startUnlessWaiting("demo", "Demo", alpha)).as("der wartende nimmt es mit").isEmpty();
        assertThat(waitingOnly.startUnlessWaiting("demo", "Demo", new BackgroundPart.Draft("wiki", "BETA")))
                .as("ein anderer Space").isPresent();

        BackgroundJobs background = background(jobs(executor), new AtomicBoolean(true));
        assertThat(background.startUnlessRunning("demo", "Demo", alpha)).isPresent();
        assertThat(background.waiting("demo", "wiki", "ALPHA")).as("läuft schon").isEmpty();
        assertThat(background.startUnlessRunning("demo", "Demo", alpha)).as("der laufende holt es sich").isEmpty();
        assertThat(background.startUnlessWaiting("demo", "Demo", alpha))
                .as("ein laufender sieht den neuen Anlass nicht mehr").isPresent();
    }

    @Test
    void aPausedPartCountsAsWaitingAndHoldsNewOccasionsBack() {
        // Abnahme #1052: Angehalten wartet ein Teil auf das Fortsetzen; ein neuer Anlass startet keinen zweiten.
        BackgroundJobs background = background(new Jobs(new InMemoryJobStore(), executor,
                new JobLimits(1, 10, Duration.ofHours(1)), clock), new AtomicBoolean(true));
        BackgroundPart.Draft alpha = new BackgroundPart.Draft("wiki", "ALPHA");
        BackgroundGroup group = background.startUnlessWaiting("demo", "Demo", alpha).orElseThrow();

        background.pause(group.groupId());

        assertThat(background.group(group.groupId()).orElseThrow().paused()).isTrue();
        assertThat(background.waiting("demo", "wiki", "ALPHA")).isPresent();
        assertThat(background.open("demo", "wiki", "ALPHA")).isPresent();
        assertThat(background.running("demo", "wiki", "ALPHA")).as("für den Planer kein Vorgänger").isEmpty();
        assertThat(background.startUnlessWaiting("demo", "Demo", alpha)).as("der angehaltene nimmt es mit").isEmpty();
        assertThat(background.startUnlessRunning("demo", "Demo", alpha)).as("der angehaltene holt es sich").isEmpty();
        assertThat(background.groups()).extracting(BackgroundGroup::groupId).containsExactly(group.groupId());
    }

    @Test
    void startUnlessRunningForAllPartsOfAnOccasionLeavesOutWhatAlreadyWaitsOrRuns() {
        // Ein Platz: der erste Teil wartet, wie ein nach dem Neustart fortgesetzter.
        BackgroundJobs background = background(new Jobs(new InMemoryJobStore(), executor,
                new JobLimits(1, 10, Duration.ofHours(1)), clock), new AtomicBoolean(true));
        background.startUnlessWaiting("demo", "Demo", new BackgroundPart.Draft("wiki", "ALPHA")).orElseThrow();
        List<BackgroundPart.Draft> both = List.of(new BackgroundPart.Draft("wiki", "ALPHA"),
                new BackgroundPart.Draft("wiki", "BETA"));

        BackgroundGroup started = background.startUnlessRunning("demo", "Beim Start", both).orElseThrow();

        assertThat(started.parts()).extracting(JobStatus::partitionKey).containsExactly("BETA");
        assertThat(started.parts()).allSatisfy(part -> assertThat(part.parameter(BackgroundJobs.TITLE))
                .isEqualTo("Beim Start"));
        assertThat(background.startUnlessRunning("demo", "Beim Start", both)).as("beide haben einen Teil").isEmpty();
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
    void anOrderlyShutdownInterruptsBackgroundJobsSoTheyResumeAfterTheRestart() {
        Jobs first = new Jobs(store, new Steps(), new JobLimits(2, 10, Duration.ofHours(1)), clock);
        BackgroundGroup group = background(first, new AtomicBoolean(true)).start("demo", "Demo", twoSpaces());
        // Der erste Teil läuft, der zweite wartet; beide unterbricht das Beenden.
        first.close();

        Jobs restarted = jobs(executor);
        List<JobStatus> resumed = background(restarted, new AtomicBoolean(true)).resumeInterrupted();
        executor.runAll();

        assertThat(resumed).extracting(JobStatus::id).containsExactlyInAnyOrderElementsOf(group.jobIds());
        assertThat(group.jobIds()).allSatisfy(id -> assertThat(restarted.status(id).state())
                .isEqualTo(JobState.COMPLETED));
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

    /**
     * Codex #1081: Ein Teil wartet auf einen wiederverwendeten Teil einer anderen Gruppe ({@link Jobs#AFTER}). Die
     * Gruppe nennt ihn als Vorgänger (nicht als eigenen Teil), und wird er angehalten, meldet sich auch die Gruppe.
     */
    @Test
    void aReusedPredecessorOfAnotherGroupIsNamedAndItsChangesReachTheWatchersOfTheGroup() throws Exception {
        Jobs jobs = jobs(executor);
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        BackgroundGroup reused = background.start("demo", "Plan", List.of(new BackgroundPart.Draft("wiki", "DEV")));
        String reusedId = reused.parts().get(0).id();
        BackgroundGroup goal = background.startSteps(BackgroundJobs.newGroupId(), "demo", "Ziel", List.of(
                new BackgroundJobs.Step("demo", "Ziel", new BackgroundPart.Draft("wiki", "DEV"), List.of(reusedId),
                        List.of(), List.of("Plan"))));
        List<BackgroundGroup> reports = new CopyOnWriteArrayList<>();

        BackgroundGroup read = background.group(goal.groupId()).orElseThrow();
        assertThat(read.parts()).extracting(JobStatus::id).doesNotContain(reusedId);
        assertThat(read.predecessors()).extracting(JobStatus::id).containsExactly(reusedId);
        assertThat(background.groups()).filteredOn(listed -> listed.groupId().equals(goal.groupId())).singleElement()
                .satisfies(listed -> assertThat(listed.predecessors()).extracting(JobStatus::id)
                        .containsExactly(reusedId));

        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        try (var subscription = background.watch(goal.groupId(), report -> {
            reports.add(report);
            if (report.predecessors().stream().anyMatch(part -> part.state() == JobState.PAUSED)) {
                held.countDown();
            }
        })) {
            background.pause(reused.groupId());
            executor.runAll();
            assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("der angehaltene Vorgänger meldet sich bei der wartenden Gruppe").isTrue();
        }
        assertThat(reports).allSatisfy(report -> assertThat(report.groupId()).isEqualTo(goal.groupId()));
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

    @Test
    void anExclusiveKindRefusesToResumeAPartWhileAnotherOfItsSpaceWaitsIsPausedOrRuns() {
        Jobs jobs = jobs(executor);
        BackgroundJobs background = exclusive(jobs);
        BackgroundPart.Draft alpha = new BackgroundPart.Draft("wiki", "ALPHA");
        BackgroundGroup cancelled = background.startUnlessRunning("solo", "Solo", alpha).orElseThrow();
        background.cancel(cancelled.groupId());
        executor.runAll();
        String cancelledId = cancelled.parts().getFirst().id();
        BackgroundGroup open = background.startUnlessRunning("solo", "Solo", alpha).orElseThrow();
        String openId = open.parts().getFirst().id();

        assertThatThrownBy(() -> background.resumeJob(cancelledId)).as("läuft schon")
                .isInstanceOfSatisfying(BackgroundPartOpenException.class,
                        refused -> assertThat(refused.openJobId()).isEqualTo(openId));
        background.resume(cancelled.groupId());
        assertThat(jobs.status(cancelledId).state()).isEqualTo(JobState.CANCELLED);

        background.pause(open.groupId());
        executor.runAll();
        assertThat(jobs.status(openId).state()).isEqualTo(JobState.PAUSED);
        assertThatThrownBy(() -> background.resumeJob(cancelledId)).as("der angehaltene nimmt die Arbeit mit")
                .isInstanceOf(BackgroundPartOpenException.class);

        background.resumeJob(openId);
        executor.runAll();
        assertThat(jobs.status(openId).state()).isEqualTo(JobState.COMPLETED);
        background.resumeJob(cancelledId);
        executor.runAll();
        assertThat(jobs.status(cancelledId).state()).as("ist keiner mehr offen, geht es").isEqualTo(JobState.COMPLETED);
        assertThat(seen).extracting(BackgroundPart::jobId).containsExactly(openId, cancelledId);
    }

    @Test
    void aKindWithoutThatLimitStillResumesNextToAnOpenPart() {
        Jobs jobs = jobs(executor);
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        BackgroundPart.Draft alpha = new BackgroundPart.Draft("wiki", "ALPHA");
        BackgroundGroup cancelled = background.start("demo", "Demo", List.of(alpha));
        background.cancel(cancelled.groupId());
        executor.runAll();
        background.start("demo", "Demo", List.of(alpha));

        background.resumeJob(cancelled.parts().getFirst().id());
        executor.runAll();

        assertThat(jobs.status(cancelled.parts().getFirst().id()).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void afterARestartAnExclusiveKindResumesOnlyTheYoungestInterruptedPartOfEachSpace() {
        // Zwei unterbrochene Teile desselben Space (je ein eigener Anlass) und einer eines anderen Space.
        Jobs first = jobs(new Steps());
        BackgroundJobs before = exclusive(first);
        String older = before.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "ALPHA")))
                .parts().getFirst().id();
        String younger = before.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "ALPHA")))
                .parts().getFirst().id();
        String beta = before.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "BETA")))
                .parts().getFirst().id();

        Jobs restarted = jobs(executor);
        List<JobStatus> resumed = exclusive(restarted).resumeInterrupted();
        executor.runAll();

        assertThat(resumed).extracting(JobStatus::id).as("der jüngste nimmt die Arbeit des älteren mit")
                .containsExactlyInAnyOrder(younger, beta);
        assertThat(restarted.status(older).state()).as("abgeschlossen statt unterbrochen liegen gelassen")
                .isEqualTo(JobState.CANCELLED);
        assertThat(restarted.events(older)).filteredOn(event -> Jobs.RETIRED.equals(event.code())).singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo(BackgroundJobs.SUPERSEDED_BY_YOUNGER));
        assertThat(restarted.status(younger).state()).isEqualTo(JobState.COMPLETED);
        assertThat(restarted.status(beta).state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    void anOlderInterruptedPartDoesNotRunAfterALaterRestartWhenTheYoungerWasCancelled() {
        // Nachprüfung #1052: Der ältere bliebe sonst unterbrochen stehen und liefe beim zweiten Neustart von selbst los.
        BackgroundJobs before = exclusive(jobs(new Steps()));
        String older = before.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "ALPHA")))
                .parts().getFirst().id();
        String younger = before.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "ALPHA")))
                .parts().getFirst().id();

        Steps firstSteps = new Steps();
        Jobs firstRestart = jobs(firstSteps);
        assertThat(exclusive(firstRestart).resumeInterrupted()).extracting(JobStatus::id).containsExactly(younger);
        firstRestart.cancel(younger, OWNER);
        firstSteps.runAll();
        assertThat(firstRestart.status(younger).state()).isEqualTo(JobState.CANCELLED);

        Jobs secondRestart = jobs(executor);
        List<JobStatus> resumed = exclusive(secondRestart).resumeInterrupted();
        executor.runAll();

        assertThat(resumed).as("der Benutzer hat abgebrochen: nichts läuft von selbst").isEmpty();
        assertThat(secondRestart.status(older).state()).isEqualTo(JobState.CANCELLED);
        assertThat(secondRestart.status(younger).state()).isEqualTo(JobState.CANCELLED);
        assertThat(seen).isEmpty();
    }

    @Test
    void interruptedPartsNextToAnotherOpenPartOfAnExclusiveKindAreClosed() {
        BackgroundJobs before = exclusive(jobs(new Steps()));
        String interrupted = before.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "ALPHA")))
                .parts().getFirst().id();

        Jobs restarted = jobs(executor);
        BackgroundJobs after = exclusive(restarted);
        String open = after.start("solo", "Solo", List.of(new BackgroundPart.Draft("wiki", "ALPHA")))
                .parts().getFirst().id();
        assertThat(after.resumeInterrupted()).as("der offene nimmt die Arbeit mit").isEmpty();
        executor.runAll();

        assertThat(restarted.status(interrupted).state()).isEqualTo(JobState.CANCELLED);
        assertThat(restarted.events(interrupted)).filteredOn(event -> Jobs.RETIRED.equals(event.code()))
                .singleElement().satisfies(event -> assertThat(event.message())
                        .isEqualTo(BackgroundJobs.SUPERSEDED_BY_OPEN));
        assertThat(restarted.status(open).state()).isEqualTo(JobState.COMPLETED);
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


    @Test
    void aPauseOrCancelOfTheUserJustBeforeTheEndIsKeptAfterTheRestartAndNeverResumed() {
        Jobs first = new Jobs(store, new Steps(), new JobLimits(4, 10, Duration.ofHours(1)), clock);
        BackgroundGroup group = background(first, new AtomicBoolean(true)).start("demo", "Demo", twoSpaces());
        String paused = group.parts().get(0).id();
        String cancelled = group.parts().get(1).id();
        // Beide laufen; der Benutzer hält den einen an und bricht den anderen ab, dann endet der Dienst, bevor die
        // Arbeit es merkt (oder er stürzt ab).
        first.pause(paused, OWNER);
        first.cancel(cancelled, OWNER);
        first.close();

        Jobs restarted = jobs(executor);
        List<JobStatus> resumed = background(restarted, new AtomicBoolean(true)).resumeInterrupted();
        executor.runAll();

        assertThat(resumed).isEmpty();
        assertThat(restarted.status(paused).state()).isEqualTo(JobState.PAUSED);
        assertThat(restarted.status(paused).pauseReason())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.PauseReason.USER);
        assertThat(restarted.status(cancelled).state()).isEqualTo(JobState.CANCELLED);
        assertThat(seen).isEmpty();
    }

    @Test
    void aRejectedPartTakesBackThePartsAlreadyAccepted() {
        Jobs jobs = jobs(executor);
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        List<BackgroundPart.Draft> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(new BackgroundPart.Draft("wiki", "S" + i, Map.of()));
        }

        assertThatThrownBy(() -> background.start("demo", "Zu viele", tooMany))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException.class);
        executor.runAll();

        assertThat(jobs.list(OWNER, JobType.BACKGROUND)).isNotEmpty().allSatisfy(status ->
                assertThat(status.state()).isEqualTo(JobState.CANCELLED));
        assertThat(seen).isEmpty();
    }

    @Test
    void aPauseOfTheUserWhileTheServiceIsClosingWinsOverInterrupted() {
        Jobs first = new Jobs(store, executor, new JobLimits(4, 10, Duration.ofHours(1)), clock);
        BackgroundGroup group = background(first, new AtomicBoolean(true)).start("demo", "Demo", twoSpaces());
        String paused = group.parts().get(0).id();
        String cancelled = group.parts().get(1).id();

        first.interruptBackground();
        first.pause(paused, OWNER);
        first.cancel(cancelled, OWNER);
        executor.runAll();

        assertThat(first.status(paused).state()).isEqualTo(JobState.PAUSED);
        assertThat(first.status(cancelled).state()).isEqualTo(JobState.CANCELLED);
        first.close();
    }

    @Test
    void afterInterruptBackgroundNothingStartsAndNothingIsAccepted() {
        Jobs jobs = new Jobs(store, executor, new JobLimits(2, 10, Duration.ofHours(1)), clock);
        BackgroundJobs background = background(jobs, new AtomicBoolean(true));
        // Ein Platz für den Hintergrund: der erste läuft, der zweite wartet.
        BackgroundGroup group = background.start("demo", "Demo", twoSpaces());
        String waiting = group.parts().get(1).id();
        assertThat(jobs.status(waiting).state()).isEqualTo(JobState.QUEUED);

        jobs.interruptBackground();
        executor.runAll();

        assertThat(jobs.status(waiting).state()).as("startet nicht mehr").isEqualTo(JobState.QUEUED);
        assertThatThrownBy(() -> background.start("demo", "Später", twoSpaces()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(background.groups()).hasSize(1);
        jobs.close();
    }
}

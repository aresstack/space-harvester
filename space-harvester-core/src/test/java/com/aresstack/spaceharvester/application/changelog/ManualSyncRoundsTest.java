package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR 0030, Bedingung 3 des Architekten: Angehaltene manuelle Synchronisierungen (auch automatisch angehaltene) halten
 * die Runde offen; die Update-Marke kommt erst, wenn keine mehr läuft oder wartet. Abgebrochen und gescheitert zählen als
 * Ende; die erste Marke einer Quelle trägt die Erstbefüllung.
 */
class ManualSyncRoundsTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);

    private final List<UpdateMark> written = new ArrayList<>();
    private final UpdateMarks marks = new UpdateMarks() {
        @Override
        public List<UpdateMark> marks(String sourceId) {
            return written.stream().filter(mark -> mark.sourceId().equals(sourceId)).toList();
        }

        @Override
        public void mark(UpdateMark mark) {
            written.add(mark);
        }
    };
    /** Der Stand in den Aufträgen, unabhängig davon, welche Meldungen schon zugestellt sind. */
    private final List<JobStatus> current = new ArrayList<>();
    private final ManualSyncRounds rounds = new ManualSyncRounds(marks, CLOCK, () -> List.copyOf(current));

    private static JobStatus sync(String id, String space, JobState state, Instant finishedAt, ChangeTrigger trigger) {
        return sync(id, space, state, finishedAt, trigger, null);
    }

    private static JobStatus sync(String id, String space, JobState state, Instant finishedAt, ChangeTrigger trigger,
                                  PauseReason reason) {
        return new JobStatus(id, JobType.SYNC, "wiki", space, "client:a", state, JobProgress.NONE,
                state == JobState.FAILED ? new JobError("SYNC_FAILED", "gescheitert") : null, false, T0, T0,
                finishedAt, null, null, Map.of(ChangeTrigger.PARAMETER, trigger.name()), false, false, reason);
    }

    @Test
    void theMarkComesWhenTheLastManualSyncOfTheSourceEnds() {
        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("b", "OPS", JobState.QUEUED, null, ChangeTrigger.MANUAL));

        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL));
        assertThat(written).isEmpty();

        rounds.changed(sync("b", "OPS", JobState.COMPLETED, T0.plusSeconds(120), ChangeTrigger.MANUAL));
        assertThat(written).singleElement().satisfies(mark -> {
            assertThat(mark.sourceId()).isEqualTo("wiki");
            assertThat(mark.at()).isEqualTo(T0.plusSeconds(120));
            assertThat(mark.user()).isEqualTo("client:a");
            assertThat(mark.spaces()).containsExactly("DEV", "OPS");
            assertThat(mark.failedSyncs()).isZero();
            assertThat(mark.initial()).isTrue();
        });
    }

    @Test
    void aPausedSyncAlsoAutoPausedKeepsTheRoundOpen() {
        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("b", "OPS", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("a", "DEV", JobState.PAUSED, null, ChangeTrigger.MANUAL, PauseReason.USER));
        rounds.changed(sync("b", "OPS", JobState.PAUSED, null, ChangeTrigger.MANUAL, PauseReason.CONNECTION_LOST));
        assertThat(written).isEmpty();

        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL));
        assertThat(written).isEmpty();

        rounds.changed(sync("b", "OPS", JobState.COMPLETED, T0.plusSeconds(90), ChangeTrigger.MANUAL));
        assertThat(written).hasSize(1);
    }

    @Test
    void cancelledFailedAndDeletedSyncsEndTheRoundAndAreCounted() {
        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("b", "OPS", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("c", "QA", JobState.PAUSED, null, ChangeTrigger.MANUAL));

        rounds.changed(sync("a", "DEV", JobState.CANCELLED, T0.plusSeconds(10), ChangeTrigger.MANUAL));
        rounds.changed(sync("b", "OPS", JobState.FAILED, T0.plusSeconds(20), ChangeTrigger.MANUAL));
        assertThat(written).isEmpty();
        rounds.removed(sync("c", "QA", JobState.PAUSED, null, ChangeTrigger.MANUAL));

        assertThat(written).singleElement().satisfies(mark -> {
            assertThat(mark.failedSyncs()).isEqualTo(3);
            // Für „Abgleich wiederholen“ (Angelo, 21.11.2026): welche Spaces gescheitert sind.
            assertThat(mark.failedSpaces()).containsExactly("DEV", "OPS", "QA");
            assertThat(mark.retrySpaces()).containsExactly("DEV", "OPS", "QA");
            assertThat(mark.at()).isEqualTo(CLOCK.instant());
        });
    }

    @Test
    void onlyTheFirstMarkOfASourceIsTheInitialLoad() {
        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL));
        rounds.changed(sync("b", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("b", "DEV", JobState.COMPLETED, T0.plusSeconds(120), ChangeTrigger.MANUAL));

        assertThat(written).extracting(UpdateMark::initial).containsExactly(true, false);
    }

    @Test
    void syncsOfOtherTriggersNeverMark() {
        for (ChangeTrigger trigger : List.of(ChangeTrigger.STARTUP, ChangeTrigger.SCHEDULE, ChangeTrigger.SEARCH,
                ChangeTrigger.UNKNOWN)) {
            rounds.changed(sync(trigger.name(), "DEV", JobState.RUNNING, null, trigger));
            rounds.changed(sync(trigger.name(), "DEV", JobState.COMPLETED, T0.plusSeconds(5), trigger));
        }
        assertThat(written).isEmpty();
    }

    @Test
    void aFastSyncThatOnlyReportsItsEndStillMarksOnceEvenIfReportedTwice() {
        JobStatus done = sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(30), ChangeTrigger.MANUAL);

        rounds.changed(done);
        rounds.changed(done);

        assertThat(written).singleElement().satisfies(mark -> assertThat(mark.at()).isEqualTo(T0.plusSeconds(30)));
    }

    @Test
    void retryingAFinishedSyncInTheSameEntryIsANewRound() {
        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(30), ChangeTrigger.MANUAL));
        rounds.changed(sync("a", "DEV", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(90), ChangeTrigger.MANUAL));

        assertThat(written).extracting(UpdateMark::at).containsExactly(T0.plusSeconds(30), T0.plusSeconds(90));
    }

    @Test
    void aSecondSyncWhoseFirstReportIsStillPendingKeepsTheRoundOpen() {
        // b läuft schon, seine erste Meldung ist aber noch gebündelt; a meldet nur sein Ende.
        current.add(sync("b", "OPS", JobState.RUNNING, null, ChangeTrigger.MANUAL));
        current.add(sync("x", "OPS", JobState.RUNNING, null, ChangeTrigger.SCHEDULE));
        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(30), ChangeTrigger.MANUAL));
        assertThat(written).isEmpty();

        current.clear();
        rounds.changed(sync("b", "OPS", JobState.COMPLETED, T0.plusSeconds(90), ChangeTrigger.MANUAL));
        assertThat(written).singleElement().satisfies(mark -> {
            assertThat(mark.at()).isEqualTo(T0.plusSeconds(90));
            assertThat(mark.spaces()).containsExactly("DEV", "OPS");
        });
    }

    @Test
    void theRecoveryAfterARestartRunsInTheBackgroundAndDoesNotHoldUpTheStart() throws Exception {
        InMemoryJobStore store = new InMemoryJobStore();
        store.save(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL));
        List<Runnable> background = new ArrayList<>();
        try (Jobs jobs = new Jobs(store, Runnable::run, new JobLimits(1, 5, Duration.ofDays(1)), CLOCK);
             ManualSyncRounds started = ManualSyncRounds.start(jobs, marks, CLOCK, background::add)) {
            assertThat(written).as("der Start liest und schreibt nichts").isEmpty();
            assertThat(background).hasSize(1);

            background.getFirst().run();
            assertThat(written).singleElement().satisfies(mark -> {
                assertThat(mark.at()).isEqualTo(T0.plusSeconds(60));
                assertThat(mark.spaces()).containsExactly("DEV");
            });
        }
    }

    @Test
    void aRecoveryThatOnlyRunsAfterClosingWritesNothing() throws Exception {
        InMemoryJobStore store = new InMemoryJobStore();
        store.save(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL));
        List<Runnable> background = new ArrayList<>();
        try (Jobs jobs = new Jobs(store, Runnable::run, new JobLimits(1, 5, Duration.ofDays(1)), CLOCK)) {
            ManualSyncRounds.start(jobs, marks, CLOCK, background::add).close();
            background.getFirst().run();
        }
        assertThat(written).isEmpty();
    }

    @Test
    void afterARestartAnEndWhoseReportWasLostStillMarks() {
        written.add(new UpdateMark("wiki", T0.plusSeconds(10), "client:a", List.of("DEV"), 0, true));

        rounds.recover(List.of(
                sync("old", "DEV", JobState.COMPLETED, T0.plusSeconds(10), ChangeTrigger.MANUAL),
                sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL),
                sync("b", "OPS", JobState.FAILED, T0.plusSeconds(50), ChangeTrigger.MANUAL),
                sync("s", "QA", JobState.COMPLETED, T0.plusSeconds(70), ChangeTrigger.SCHEDULE)));

        assertThat(written).hasSize(2);
        assertThat(written.get(1)).satisfies(mark -> {
            assertThat(mark.at()).isEqualTo(T0.plusSeconds(60));
            assertThat(mark.spaces()).containsExactly("DEV", "OPS");
            assertThat(mark.failedSyncs()).isEqualTo(1);
            assertThat(mark.initial()).isFalse();
        });
        // Die nachgeholte Meldung zählt nicht noch einmal.
        rounds.changed(sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL));
        assertThat(written).hasSize(2);
    }

    @Test
    void afterARestartALostEndJoinsTheRoundOfAStillPausedSync() {
        rounds.recover(List.of(
                sync("a", "DEV", JobState.COMPLETED, T0.plusSeconds(60), ChangeTrigger.MANUAL),
                sync("b", "OPS", JobState.PAUSED, null, ChangeTrigger.MANUAL, PauseReason.CONNECTION_LOST)));
        assertThat(written).isEmpty();

        rounds.changed(sync("b", "OPS", JobState.COMPLETED, T0.plusSeconds(120), ChangeTrigger.MANUAL));
        assertThat(written).singleElement().satisfies(mark -> {
            assertThat(mark.initial()).isTrue();
            assertThat(mark.spaces()).containsExactly("DEV", "OPS");
        });
    }
}

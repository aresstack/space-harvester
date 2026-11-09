package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Langsame Wiederholung von „Modelle laden“: 5 s, 10 s, 20 s … höchstens 60 s, nie bei abgelehntem Key. */
class ModelRetryTest {

    /** Zeitplanung ohne echtes Warten: merkt sich Wartezeiten und führt die Aufgabe auf Wunsch aus. */
    static final class FakeScheduler implements ModelRetry.Scheduler {
        final List<Duration> delays = new ArrayList<>();
        Runnable pending;
        int cancelled;

        @Override
        public Runnable schedule(Duration delay, Runnable task) {
            delays.add(delay);
            pending = task;
            return () -> {
                cancelled++;
                pending = null;
            };
        }

        void fire() {
            Runnable task = pending;
            pending = null;
            task.run();
        }
    }

    @Test
    void theScheduleDoublesFromFiveSecondsUpToOneMinute() {
        assertThat(List.of(1, 2, 3, 4, 5, 6, 40).stream().map(ModelRetry::delay).map(Duration::toSeconds).toList())
                .containsExactly(5L, 10L, 20L, 40L, 60L, 60L, 60L);
    }

    @Test
    void failuresAreRetriedUntilSuccessAndTheStatusNamesAttemptAndWait() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ModelRetry.Outcome> attempts = new ArrayList<>();
        List<String> status = new ArrayList<>();
        ModelRetry retry = new ModelRetry(scheduler, attempts::add,
                (n, next) -> status.add("Versuch " + n + " – nächster in " + next.toSeconds() + " s"));

        retry.start();
        assertThat(attempts).hasSize(1);
        for (int i = 0; i < 3; i++) {
            attempts.get(attempts.size() - 1).failed(true);
            scheduler.fire();
        }
        assertThat(attempts).hasSize(4);
        assertThat(scheduler.delays).extracting(Duration::toSeconds).containsExactly(5L, 10L, 20L);
        assertThat(status).containsExactly("Versuch 1 – nächster in 5 s", "Versuch 2 – nächster in 10 s",
                "Versuch 3 – nächster in 20 s");
        attempts.get(3).succeeded();
        assertThat(retry.active()).isFalse();
        assertThat(scheduler.pending).isNull();
    }

    @Test
    void aNonRetryableFailureStopsAndManualStartResetsTheWait() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ModelRetry.Outcome> attempts = new ArrayList<>();
        ModelRetry retry = new ModelRetry(scheduler, attempts::add, (n, next) -> { });
        retry.start();
        attempts.get(0).failed(true);
        scheduler.fire();
        attempts.get(1).failed(false);
        assertThat(retry.active()).as("abgelehnter Key: kein weiterer Versuch").isFalse();
        assertThat(scheduler.pending).isNull();

        retry.start();
        attempts.get(2).failed(true);
        assertThat(scheduler.delays).extracting(Duration::toSeconds).containsExactly(5L, 5L);
    }

    @Test
    void stoppingCancelsTheWaitAndIgnoresALateAnswer() {
        FakeScheduler scheduler = new FakeScheduler();
        List<ModelRetry.Outcome> attempts = new ArrayList<>();
        ModelRetry retry = new ModelRetry(scheduler, attempts::add, (n, next) -> { });
        retry.start();
        attempts.get(0).failed(true);
        retry.stop();
        assertThat(scheduler.cancelled).isEqualTo(1);
        assertThat(retry.active()).isFalse();

        retry.start();
        ModelRetry.Outcome inFlight = attempts.get(1);
        retry.stop();
        inFlight.failed(true);
        assertThat(scheduler.pending).as("Schritt verlassen: nichts mehr einplanen").isNull();
        assertThat(attempts).hasSize(2);
    }
}

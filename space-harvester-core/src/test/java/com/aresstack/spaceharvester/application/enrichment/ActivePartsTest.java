package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Das Schließen wartet auf laufende Teile, ohne dass Wartende ihren Träger festhalten. */
class ActivePartsTest {

    private final ActiveParts parts = new ActiveParts();

    @Test
    void manyWaitersOnVirtualThreadsNeverHoldTheirCarriers() throws Exception {
        // Architekt 22.11.2026: Mit synchronized und timedWait hielte jeder Wartende seinen Träger fest.
        int waiters = 400;
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread part = Thread.ofPlatform().daemon().start(() -> parts.run(() -> false, () -> { }, () -> {
            running.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        CountDownLatch entered = new CountDownLatch(waiters);
        List<Future<Boolean>> waiting = new ArrayList<>();
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < waiters; i++) {
                waiting.add(virtual.submit(() -> {
                    entered.countDown();
                    return parts.await(Duration.ofMinutes(1));
                }));
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(virtual.submit(() -> "frei").get(10, TimeUnit.SECONDS)).isEqualTo("frei");

            release.countDown();
            for (Future<Boolean> each : waiting) {
                assertThat(each.get(10, TimeUnit.SECONDS)).isTrue();
            }
        }
        part.join(5_000);
    }

    @Test
    void waitingEndsAfterItsTimeWhileAPartStillRuns() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread part = Thread.ofPlatform().daemon().start(() -> parts.run(() -> false, () -> { }, () -> {
            running.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(parts.await(Duration.ofMillis(50))).isFalse();
        release.countDown();
        part.join(5_000);
        assertThat(parts.await(Duration.ZERO)).isTrue();
    }
}

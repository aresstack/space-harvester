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
        List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < waiters; i++) {
                    waiting.add(virtual.submit(() -> {
                        threads.add(Thread.currentThread());
                        entered.countDown();
                        return parts.await(Duration.ofMinutes(1));
                    }));
                }
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(allParked(threads)).isTrue();
                assertThat(virtual.submit(() -> "frei").get(10, TimeUnit.SECONDS)).isEqualTo("frei");

                release.countDown();
                for (Future<Boolean> each : waiting) {
                    assertThat(each.get(10, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                // Scheitert eine Frist, wartet close() des Executors sonst auf die Wartenden (Abnahme #1133).
                release.countDown();
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
        try {
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(parts.await(Duration.ofMillis(50))).isFalse();
        } finally {
            release.countDown();
        }
        part.join(5_000);
        assertThat(parts.await(Duration.ZERO)).isTrue();
    }

    /** Ob alle Threads binnen zehn Sekunden parken (WAITING oder TIMED_WAITING); ohne Schlafen. */
    private static boolean allParked(List<Thread> threads) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (threads.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }
}

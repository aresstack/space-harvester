package com.aresstack.spaceharvester.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InFlightLimitTest {

    @Test
    void startsAtMostTheLimitAndTheNextOneWhenASlotFrees() {
        InFlightLimit limit = new InFlightLimit(2);
        List<CompletableFuture<String>> started = new ArrayList<>();

        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(limit.submit(() -> {
                CompletableFuture<String> request = new CompletableFuture<>();
                started.add(request);
                return request;
            }));
        }

        assertThat(started).hasSize(2);
        assertThat(limit.running()).isEqualTo(2);
        started.get(0).complete("a");
        assertThat(results.get(0)).isCompletedWithValue("a");
        assertThat(started).hasSize(3);
        started.get(1).completeExceptionally(new IllegalStateException("kaputt"));
        assertThat(results.get(1)).isCompletedExceptionally();
        assertThat(started).hasSize(4);
        started.get(2).complete("x");
        started.get(3).complete("x");
        started.get(4).complete("y");
        assertThat(results.get(4)).isCompletedWithValue("y");
        assertThat(limit.running()).isZero();
    }

    @Test
    void aWaitingCancelledStageNeverStartsAndARunningOneIsCancelled() {
        InFlightLimit limit = new InFlightLimit(1);
        CompletableFuture<String> first = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();

        CompletableFuture<String> running = limit.submit(() -> {
            starts.incrementAndGet();
            return first;
        });
        CompletableFuture<String> waiting = limit.submit(() -> {
            starts.incrementAndGet();
            return new CompletableFuture<>();
        });
        waiting.cancel(true);
        running.cancel(true);

        assertThat(first).isCancelled();
        assertThat(starts).hasValue(1);
        assertThat(limit.running()).isZero();
    }

    @Test
    void manySynchronouslyEndingRequestsNeitherNestNorLeakSlots() {
        InFlightLimit limit = new InFlightLimit(1);
        CompletableFuture<String> blocker = new CompletableFuture<>();
        limit.submit(() -> blocker);
        List<CompletableFuture<String>> results = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            results.add(limit.submit(() -> CompletableFuture.failedFuture(new IllegalStateException())));
        }

        blocker.complete("frei");

        assertThat(results).allSatisfy(result -> assertThat(result).isCompletedExceptionally());
        assertThat(limit.running()).isZero();
    }

    @Test
    void concurrentCompletionsKeepTheLimit() throws Exception {
        InFlightLimit limit = new InFlightLimit(3);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(2_000);
        try {
            for (int i = 0; i < 2_000; i++) {
                limit.submit(() -> {
                    highest.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    return CompletableFuture.supplyAsync(() -> {
                        concurrent.decrementAndGet();
                        return "ok";
                    }, pool);
                }).whenComplete((value, error) -> done.countDown());
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(highest.get()).isLessThanOrEqualTo(3);
        // Die Stufe des Aufrufers endet kurz vor der Freigabe des Platzes; der letzte Platz wird also eventuell erst
        // nach done.countDown() frei.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (limit.running() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(limit.running()).isZero();
    }

    @Test
    void theLimitIsAtLeastOne() {
        assertThatThrownBy(() -> new InFlightLimit(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

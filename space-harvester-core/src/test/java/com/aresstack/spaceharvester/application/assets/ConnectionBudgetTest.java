package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Gesamtbudget der Verbindungen für Bilder und Anhänge, nach Restzeit verteilt (Angelo 11.11.2026). */
class ConnectionBudgetTest {

    private static final JobContext NOTHING = new StopContext();

    @Test
    void manyJobsWaitingForConnectionsOnVirtualThreadsNeverHoldTheirCarriers() throws Exception {
        // Architekt 22.11.2026: Mit synchronized und wait() hielte jeder Wartende seinen Träger fest.
        int waiters = 400;
        ConnectionBudget budget = new ConnectionBudget(0);
        CountDownLatch entered = new CountDownLatch(waiters);
        java.util.List<java.util.concurrent.Future<?>> waiting = new java.util.ArrayList<>();
        try (java.util.concurrent.ExecutorService virtual =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < waiters; i++) {
                waiting.add(virtual.submit(() -> {
                    entered.countDown();
                    budget.awaitAvailable(NOTHING);
                    return null;
                }));
            }
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(virtual.submit(() -> "frei").get(10, TimeUnit.SECONDS)).isEqualTo("frei");

                budget.total(4);
                for (java.util.concurrent.Future<?> each : waiting) {
                    each.get(10, TimeUnit.SECONDS);
                }
            } finally {
                // Scheitert eine Frist, wartet close() sonst ewig auf die Wartenden.
                budget.total(4);
            }
        }
    }

    @Test
    void aSingleDownloadGetsTheWholeBudget() {
        ConnectionBudget budget = new ConnectionBudget(16);

        try (ConnectionBudget.Lease lease = budget.register(() -> 1_000)) {
            assertThat(lease.share()).isEqualTo(16);
        }
    }

    @Test
    void theBudgetFollowsTheRemainingTimeAndChangesWithIt() {
        ConnectionBudget budget = new ConnectionBudget(8);
        AtomicLong slow = new AtomicLong(30_000);
        try (ConnectionBudget.Lease a = budget.register(slow::get);
             ConnectionBudget.Lease b = budget.register(() -> 10_000)) {
            assertThat(a.share()).isEqualTo(6);
            assertThat(b.share()).isEqualTo(2);

            // Der erste holt auf: die Verbindungen wandern zum anderen, damit beide zugleich fertig werden.
            slow.set(10_000);
            assertThat(a.share()).isEqualTo(4);
            assertThat(b.share()).isEqualTo(4);
        }
    }

    @Test
    void everyDownloadKeepsAtLeastOneConnectionAndTheTotalIsNeverExceeded() {
        ConnectionBudget budget = new ConnectionBudget(3);
        try (ConnectionBudget.Lease big = budget.register(() -> 1_000_000);
             ConnectionBudget.Lease small = budget.register(() -> 1);
             ConnectionBudget.Lease done = budget.register(() -> 0)) {
            assertThat(small.share()).isEqualTo(1);
            assertThat(done.share()).isEqualTo(1);
            assertThat(big.share()).isEqualTo(1);
        }
    }

    @Test
    void aDownloadWaitsForAFreeConnectionOfItsShare() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(1);
        ConnectionBudget.Lease lease = budget.register(() -> 1);
        lease.acquire(NOTHING);
        CountDownLatch second = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            lease.acquire(NOTHING);
            second.countDown();
        });
        waiting.start();

        assertThat(second.await(300, TimeUnit.MILLISECONDS)).isFalse();
        lease.release();
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
        lease.release();
        lease.close();
    }

    @Test
    void zeroLoadsNothingUntilConnectionsAreBack() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(0);
        ConnectionBudget.Lease lease = budget.register(() -> 1);
        assertThat(lease.share()).isZero();
        CountDownLatch loaded = new CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            budget.awaitAvailable(NOTHING);
            lease.acquire(NOTHING);
            loaded.countDown();
        });
        waiting.start();

        assertThat(loaded.await(300, TimeUnit.MILLISECONDS)).isFalse();
        budget.total(2);
        assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
        lease.release();
        lease.close();
    }

    @Test
    void theCheckpointEndsAWait() {
        ConnectionBudget budget = new ConnectionBudget(0);
        StopContext paused = new StopContext();
        paused.cancel();

        assertThatThrownBy(() -> budget.awaitAvailable(paused)).isInstanceOf(JobCancelledException.class);
        assertThatThrownBy(() -> new ConnectionBudget(65)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRuntimeLimitEndsAWaitForTheBudgetEvenThoughNobodySignals() {
        // Codex-Review #1133: Die Laufzeitgrenze meldet sich nicht; ohne Frist hielte der Auftrag bei Budget 0 ewig
        // seinen Download-Platz.
        ConnectionBudget budget = new ConnectionBudget(0, java.time.Duration.ofMillis(20));
        StopContext limited = new StopContext();
        java.util.concurrent.CompletableFuture<Void> waiter = java.util.concurrent.CompletableFuture.runAsync(
                () -> budget.awaitAvailable(limited));
        while (limited.listeners.isEmpty()) {
            Thread.onSpinWait();
        }

        limited.expired = true;

        assertThat(waiter).failsWithin(5, TimeUnit.SECONDS).withThrowableThat().havingCause()
                .isInstanceOfSatisfying(JobFailedException.class, e -> assertThat(e.code()).isEqualTo("TIMEOUT"));
        assertThat(limited.listeners).isEmpty();
    }

    @Test
    void stoppingWakesWaitersAtOnceWithoutATick() throws Exception {
        // ADR 0027: Anhalten oder Abbrechen weckt; das Budget bleibt 0, und kein Takt sieht nach.
        ConnectionBudget budget = new ConnectionBudget(0);
        StopContext waiting = new StopContext();
        java.util.concurrent.CompletableFuture<Void> waiter = java.util.concurrent.CompletableFuture.runAsync(
                () -> budget.awaitAvailable(waiting));
        while (waiting.listeners.isEmpty()) {
            Thread.onSpinWait();
        }

        waiting.cancel();

        assertThat(waiter).failsWithin(5, TimeUnit.SECONDS).withThrowableThat().havingCause()
                .isInstanceOf(JobCancelledException.class);
        assertThat(waiting.listeners).isEmpty();
    }

    @Test
    void releasingAConnectionLetsTheNextDownloadStartAtOnce() throws Exception {
        // Freigeben signalisiert: der Wartende läuft weiter, ohne auf die Frist der Anteile zu warten. Die Frist ist
        // hier eine Stunde, also weckt ihn nur das Signal; keine Zeitmessung.
        ConnectionBudget budget = new ConnectionBudget(1, java.time.Duration.ofHours(1));
        ConnectionBudget.Lease lease = budget.register(() -> 1);
        lease.acquire(NOTHING);
        CountDownLatch waiting = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        Thread second = new Thread(() -> {
            waiting.countDown();
            lease.acquire(NOTHING);
            started.set(true);
        });
        second.setDaemon(true);
        second.start();
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        while (second.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }

        lease.release();
        second.join(5_000);

        assertThat(started).isTrue();
        lease.release();
        lease.close();
    }

    private static final class StopContext implements JobContext {

        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        /** Laufzeitgrenze abgelaufen, wie in {@code Jobs}: still, ohne Zuhörer zu wecken. */
        volatile boolean expired;

        void cancel() {
            cancelled = true;
            for (Runnable listener : listeners) {
                if (listeners.remove(listener)) {
                    listener.run();
                }
            }
        }

        @Override
        public AutoCloseable whenStopRequested(Runnable listener) {
            listeners.add(listener);
            if (cancelled && listeners.remove(listener)) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        @Override
        public String jobId() {
            return "job-1";
        }

        @Override
        public void checkpoint() {
            if (cancelled) {
                throw new JobCancelledException("job-1");
            }
            if (expired) {
                throw new JobFailedException("TIMEOUT", "Laufzeitgrenze überschritten");
            }
        }

        @Override
        public boolean cancelRequested() {
            return cancelled;
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
    }
}

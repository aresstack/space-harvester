package com.aresstack.spaceharvester.application.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Warten auf das Netz in einem laufenden Auftrag; Fristen von Hand, gewartet wird nur auf Signale, nie auf Zeit. */
class NetworkWaitTest {

    private static final Instant T0 = Instant.parse("2026-11-22T21:00:00Z");

    private final NetworkStateTest.ManualClock clock = new NetworkStateTest.ManualClock(T0);
    private final NetworkStateTest.ManualTimer timer = new NetworkStateTest.ManualTimer();
    private final NetworkState network = new NetworkState(clock, timer);
    private final FakeContext context = new FakeContext();
    private final NetworkWait wait = new NetworkWait(network, context, "Anhänge vorladen", clock);

    @AfterEach
    void close() {
        network.close();
    }

    private static IOException connectionRefused() {
        return new IOException("Abruf von wiki.example scheiterte", new ConnectException("Connection refused"));
    }

    @Test
    void aFailureOfTheMatterIsNotTheNetwork() {
        assertThat(wait.failed(new IllegalStateException("HTTP 404 für anhang-1.pdf"))).isFalse();

        assertThat(context.events).isEmpty();
        assertThat(context.awaiting).isEmpty();
        assertThat(network.online()).isTrue();
    }

    @Test
    void theJobWaitsVisiblyAndContinuesWhenTheNetworkAnswersAgain() throws Exception {
        CompletableFuture<Boolean> worker = CompletableFuture.supplyAsync(() -> wait.failed(connectionRefused()));
        assertThat(context.waiting.await(10, TimeUnit.SECONDS)).isTrue();

        timer.fireDue();

        assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(context.awaiting).containsExactly(true);
        assertThat(context.events).singleElement().satisfies(event -> {
            assertThat(event.code()).isEqualTo(NetworkWait.OFFLINE);
            assertThat(event.severity()).isEqualTo(JobEvent.Severity.WARNING);
            assertThat(event.message()).contains("ConnectException", "nichts zählt als gescheitert");
        });

        clock.advance(Duration.ofSeconds(5));
        wait.answered();

        assertThat(network.online()).isTrue();
        assertThat(context.awaiting).containsExactly(true, false);
        assertThat(context.events).last().satisfies(event -> {
            assertThat(event.code()).isEqualTo(NetworkWait.ONLINE);
            assertThat(event.message()).contains("nach 5 s");
        });
    }

    @Test
    void theGrantedCheckCoversTheNextFetchAndAFailedCheckWaitsAgain() throws Exception {
        CompletableFuture<Boolean> worker = CompletableFuture.supplyAsync(() -> {
            boolean network = wait.failed(connectionRefused());
            // Dasselbe Element erneut: die erteilte Erlaubnis gilt, keine zweite nötig.
            wait.beforeFetch();
            return network;
        });
        assertThat(context.waiting.await(10, TimeUnit.SECONDS)).isTrue();
        timer.fireDue();
        assertThat(worker.get(10, TimeUnit.SECONDS)).isTrue();

        // Die Prüfung scheitert wieder am Netz (vom Dienst schon gemeldet): wieder warten, bis zur nächsten Frist.
        network.failed(connectionRefused());
        CompletableFuture<Void> again = CompletableFuture.runAsync(wait::offline);
        assertThat(again).isNotDone();
        timer.fireDue();
        again.get(10, TimeUnit.SECONDS);
        assertThat(timer.delays()).contains(Duration.ofSeconds(10));
        assertThat(context.events).extracting(JobEvent.Draft::code).containsExactly(NetworkWait.OFFLINE);
    }

    @Test
    void beforeFetchPassesStraightThroughWhileOnline() {
        wait.beforeFetch();

        assertThat(context.events).isEmpty();
    }

    @Test
    void aStopEndsTheWait() throws Exception {
        CompletableFuture<Throwable> worker = CompletableFuture.supplyAsync(() -> {
            try {
                wait.failed(connectionRefused());
                return null;
            } catch (JobCancelledException e) {
                return e;
            }
        });
        assertThat(context.waiting.await(10, TimeUnit.SECONDS)).isTrue();

        context.stop();

        assertThat(worker.get(10, TimeUnit.SECONDS)).isInstanceOf(JobCancelledException.class);
        assertThat(network.online()).isFalse();
    }

    /** Ein Auftrag ohne Jobs-Dienst; zählt mit, was der Auftrag meldet. */
    private static final class FakeContext implements JobContext {
        final List<JobEvent.Draft> events = new CopyOnWriteArrayList<>();
        final List<Boolean> awaiting = new CopyOnWriteArrayList<>();
        final CountDownLatch waiting = new CountDownLatch(1);
        private final List<Runnable> stopListeners = new CopyOnWriteArrayList<>();
        private volatile boolean stopped;

        void stop() {
            stopped = true;
            stopListeners.forEach(Runnable::run);
        }

        @Override
        public String jobId() {
            return "job-netz-1";
        }

        @Override
        public void checkpoint() {
            if (stopped) {
                throw new JobCancelledException(jobId());
            }
        }

        @Override
        public boolean cancelRequested() {
            return stopped;
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

        @Override
        public void record(JobEvent.Draft event) {
            events.add(event);
        }

        @Override
        public void awaitingNetwork(boolean waitingNow) {
            awaiting.add(waitingNow);
            if (waitingNow) {
                waiting.countDown();
            }
        }

        @Override
        public AutoCloseable whenStopRequested(Runnable listener) {
            stopListeners.add(listener);
            if (stopped) {
                listener.run();
            }
            return () -> stopListeners.remove(listener);
        }
    }
}

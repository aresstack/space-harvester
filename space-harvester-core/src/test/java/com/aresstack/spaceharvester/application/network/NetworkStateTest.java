package com.aresstack.spaceharvester.application.network;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobTimer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Netzzustand: offline, eine Prüfung je Frist, wachsende Fristen, zurück; Zeit und Fristen von Hand, ohne Warten. */
class NetworkStateTest {

    private static final Instant T0 = Instant.parse("2026-11-22T21:00:00Z");

    private final ManualClock clock = new ManualClock(T0);
    private final ManualTimer timer = new ManualTimer();
    private final NetworkState network = new NetworkState(clock, timer);

    private static IOException connectionRefused() {
        return new IOException("Abruf von wiki.example scheiterte", new ConnectException("Connection refused"));
    }

    @Test
    void onlyFailuresOfTheNetworkCount() {
        assertThat(NetworkState.networkFailure(connectionRefused())).isTrue();
        assertThat(NetworkState.networkFailure(new UnknownHostException("wiki.example"))).isTrue();
        assertThat(NetworkState.networkFailure(new NoRouteToHostException("keine Route"))).isTrue();
        assertThat(NetworkState.networkFailure(new HttpConnectTimeoutException("connect timed out"))).isTrue();
        assertThat(NetworkState.networkFailure(new SocketException("Network is unreachable"))).isTrue();
        assertThat(NetworkState.networkFailure(new IllegalStateException("HTTP 503 von wiki.example"))).isFalse();
        assertThat(NetworkState.networkFailure(new SocketTimeoutException("Read timed out"))).isFalse();
        assertThat(NetworkState.networkFailure(new SocketException("Connection reset"))).isFalse();
        assertThat(NetworkState.networkFailure(null)).isFalse();
        assertThat(network.failed(new IllegalStateException("HTTP 404"))).isFalse();
        assertThat(network.online()).isTrue();
    }

    @Test
    void theWaitGrowsFromFiveSecondsToFiveMinutes() {
        assertThat(NetworkState.delay(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(NetworkState.delay(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(NetworkState.delay(5)).isEqualTo(Duration.ofSeconds(160));
        assertThat(NetworkState.delay(6)).isEqualTo(Duration.ofMinutes(5));
        assertThat(NetworkState.delay(40)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aNetworkFailureGoesOfflineTellsTheObserversAndPlansExactlyOneCheck() {
        List<NetworkState.Status> seen = new ArrayList<>();
        network.subscribe(seen::add);

        assertThat(network.failed(connectionRefused())).isTrue();
        assertThat(network.failed(new UnknownHostException("wiki.example"))).isTrue();

        assertThat(network.online()).isFalse();
        assertThat(network.status()).isEqualTo(new NetworkState.Status(false, "UnknownHostException", T0,
                T0.plusSeconds(5)));
        assertThat(seen).extracting(NetworkState.Status::online).containsExactly(false);
        assertThat(timer.delays()).containsExactly(Duration.ofSeconds(5));
        assertThat(network.tryPermit()).isFalse();
        assertThat(network.permit()).isNotDone();
    }

    @Test
    void afterTheWaitOnlyTheFirstWaiterChecksAndAnAnswerReleasesEveryone() {
        network.failed(connectionRefused());
        CompletableFuture<Void> first = network.permit();
        CompletableFuture<Void> second = network.permit();

        timer.fireDue();

        assertThat(first).isCompleted();
        assertThat(second).isNotDone();
        assertThat(network.tryPermit()).isFalse();

        network.succeeded();

        assertThat(second).isCompleted();
        assertThat(network.online()).isTrue();
        assertThat(network.status()).isEqualTo(NetworkState.Status.ONLINE);
        assertThat(network.tryPermit()).isTrue();
    }

    @Test
    void aFailedCheckWaitsLongerEachTime() {
        network.failed(connectionRefused());
        timer.fireDue();
        assertThat(network.tryPermit()).isTrue();
        assertThat(network.tryPermit()).isFalse();

        clock.advance(Duration.ofSeconds(5));
        network.failed(connectionRefused());
        timer.fireDue();
        assertThat(network.tryPermit()).isTrue();
        network.failed(connectionRefused());

        assertThat(timer.delays()).containsSubsequence(Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(20));
        assertThat(network.status().since()).isEqualTo(T0);
    }

    @Test
    void anUnclaimedCheckWaitsForTheNextCallerWithoutTicking() {
        network.failed(connectionRefused());
        timer.fireDue();

        assertThat(timer.pending()).isZero();
        assertThat(network.permit()).isCompleted();
        assertThat(timer.pending()).isEqualTo(1);
    }

    @Test
    void aCheckThatNeverReportsExpiresWithTheNextWait() {
        network.failed(connectionRefused());
        timer.fireDue();
        assertThat(network.tryPermit()).isTrue();
        CompletableFuture<Void> later = network.permit();

        timer.fireDue();

        assertThat(later).isCompleted();
        assertThat(timer.delays()).last().isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aCancelledWaiterIsSkipped() {
        network.failed(connectionRefused());
        CompletableFuture<Void> gone = network.permit();
        CompletableFuture<Void> next = network.permit();
        gone.cancel(false);

        timer.fireDue();

        assertThat(next).isCompleted();
    }

    @Test
    void closingReleasesWaitersWithCancellation() {
        network.failed(connectionRefused());
        CompletableFuture<Void> waiter = network.permit();

        network.close();

        assertThat(waiter).isCompletedExceptionally();
        assertThat(waiter.handle((ignored, e) -> e)).isCompletedWithValueMatching(
                e -> e instanceof CancellationException);
    }

    @Test
    void aStaleTimerAfterReturningOnlineDoesNothing() {
        network.failed(connectionRefused());
        network.succeeded();
        List<NetworkState.Status> seen = new ArrayList<>();
        network.subscribe(seen::add);

        timer.fireDue();

        assertThat(network.online()).isTrue();
        assertThat(seen).isEmpty();
    }

    /** Fristen von Hand. */
    static final class ManualTimer implements JobTimer {
        private final List<Duration> delays = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public synchronized void schedule(Duration delay, Runnable task) {
            delays.add(delay);
            tasks.add(task);
        }

        synchronized List<Duration> delays() {
            return List.copyOf(delays);
        }

        synchronized int pending() {
            return tasks.size();
        }

        /** Lässt alle bis jetzt geplanten Fristen ablaufen; überholte tun nichts. */
        void fireDue() {
            List<Runnable> due;
            synchronized (this) {
                due = new ArrayList<>(tasks);
                tasks.clear();
            }
            due.forEach(Runnable::run);
        }
    }

    /** Eine Uhr, die nur auf Zuruf weitergeht. */
    static final class ManualClock extends Clock {
        private volatile Instant now;

        ManualClock(Instant start) {
            now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

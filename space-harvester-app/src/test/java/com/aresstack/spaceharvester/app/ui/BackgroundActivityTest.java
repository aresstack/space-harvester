package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackgroundActivityTest {

    @Test
    void queuedAndNestedCompletionsRemainPending() throws Exception {
        BackgroundActivity activity = new BackgroundActivity();
        AtomicInteger completed = new AtomicInteger();
        Runnable[] continuation = new Runnable[1];
        Runnable first = activity.track(() -> continuation[0] = activity.track(completed::incrementAndGet));
        assertThatThrownBy(() -> activity.awaitIdle(Duration.ZERO)).isInstanceOf(TimeoutException.class);
        first.run();
        assertThatThrownBy(() -> activity.awaitIdle(Duration.ZERO)).isInstanceOf(TimeoutException.class);
        continuation[0].run();
        activity.awaitIdle(Duration.ZERO);
        assertThat(completed.get()).isEqualTo(1);
    }

    @Test
    void completionCanStartMoreWorkAndAwaitIdleWaitsForItsUiCallback() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        long mark = Background.mark();
        SwingUtilities.invokeAndWait(() -> Background.run(() -> 1,
                first -> Background.run(() -> first + 1, completed::set,
                        failure -> completed.set(-1)), failure -> completed.set(-1)));
        Background.awaitIdle(mark, Duration.ofSeconds(10));
        assertThat(completed.get()).isEqualTo(2);
    }

    @Test
    void workLeftFromBeforeAMarkDoesNotHoldItButAllOfItsOwnWorkDoes() throws Exception {
        // Release #1492: MainWindowTest wartete auf Arbeit, die eine frühere Testklasse in derselben JVM hinterließ.
        BackgroundActivity activity = new BackgroundActivity();
        Runnable[] leftCompletion = new Runnable[1];
        Runnable left = activity.track(() -> leftCompletion[0] = activity.track(() -> { }));
        long mark = activity.mark();
        activity.awaitIdle(mark, Duration.ZERO);
        assertThatThrownBy(() -> activity.awaitIdle(Duration.ZERO)).isInstanceOf(TimeoutException.class);

        Runnable[] ownCompletion = new Runnable[1];
        Runnable own = activity.track(() -> ownCompletion[0] = activity.track(() -> { }));
        assertThatThrownBy(() -> activity.awaitIdle(mark, Duration.ZERO)).isInstanceOf(TimeoutException.class);
        left.run();
        // Die Rückmeldung der alten Arbeit gehört zu deren Marke, auch wenn sie nach der neuen Marke entsteht.
        assertThatThrownBy(() -> activity.awaitIdle(mark, Duration.ZERO)).isInstanceOf(TimeoutException.class)
                .hasMessageStartingWith("Still pending background tasks or UI callbacks: 1 (running: 0, not started: 1)");
        own.run();
        // Die Folgearbeit der eigenen Arbeit zählt weiter zur eigenen Marke.
        assertThatThrownBy(() -> activity.awaitIdle(mark, Duration.ZERO)).isInstanceOf(TimeoutException.class);
        ownCompletion[0].run();
        activity.awaitIdle(mark, Duration.ZERO);
        assertThatThrownBy(() -> activity.awaitIdle(Duration.ZERO)).isInstanceOf(TimeoutException.class);
        leftCompletion[0].run();
        activity.awaitIdle(Duration.ZERO);
    }

    @Test
    void aTimeoutNamesTheStackOfTheRunningTask() throws Exception {
        BackgroundActivity activity = new BackgroundActivity();
        long mark = activity.mark();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread worker = new Thread(activity.track(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), "blocked-task");
        worker.start();
        running.await();
        try {
            assertThatThrownBy(() -> activity.awaitIdle(mark, Duration.ZERO)).isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("(running: 1, not started: 0)")
                    .hasMessageContaining("blocked-task")
                    .hasMessageContaining("CountDownLatch.await");
        } finally {
            release.countDown();
            worker.join();
        }
        activity.awaitIdle(mark, Duration.ZERO);
    }
}

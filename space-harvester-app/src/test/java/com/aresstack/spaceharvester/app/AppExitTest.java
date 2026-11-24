package com.aresstack.spaceharvester.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppExitTest {

    @AfterEach
    void reset() {
        AppExit.resetForTest();
        System.clearProperty(AppExit.TIMEOUT_PROPERTY);
    }

    @Test
    void hangingStepDoesNotBlockBeyondTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger after = new AtomicInteger();
        long start = System.nanoTime();
        AppExit.cleanUp(List.of(() -> {
            try {
                never.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, after::incrementAndGet), Duration.ofMillis(300));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(millis < 5_000, "Aufräumen wartete " + millis + " ms");
        never.countDown();
    }

    @Test
    void failingStepDoesNotStopTheOthers() {
        AtomicInteger ran = new AtomicInteger();
        AppExit.cleanUp(List.of(() -> {
            throw new IllegalStateException("kaputt");
        }, ran::incrementAndGet), Duration.ofSeconds(5));
        assertEquals(1, ran.get());
    }

    @Test
    void cleansUpOnlyOnce() {
        AtomicInteger ran = new AtomicInteger();
        AppExit.cleanUp(List.of(ran::incrementAndGet), Duration.ofSeconds(5));
        AppExit.cleanUp(List.of(ran::incrementAndGet), Duration.ofSeconds(5));
        assertEquals(1, ran.get());
    }

    @Test
    void timeoutComesFromSystemProperty() {
        assertEquals(AppExit.DEFAULT_TIMEOUT, AppExit.timeout());
        System.setProperty(AppExit.TIMEOUT_PROPERTY, "7");
        assertEquals(Duration.ofSeconds(7), AppExit.timeout());
        System.setProperty(AppExit.TIMEOUT_PROPERTY, "nein");
        assertEquals(AppExit.DEFAULT_TIMEOUT, AppExit.timeout());
    }

    @Test
    void stopsChildProcesses() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        // Ein Kind, das von selbst nie endet.
        Process sleeper = new ProcessBuilder(java, "-cp",
                System.getProperty("java.class.path"), Sleeper.class.getName()).start();
        assertTrue(sleeper.isAlive());
        AppExit.stopChildren(Duration.ofSeconds(2));
        assertTrue(sleeper.waitFor(10, TimeUnit.SECONDS));
        assertFalse(sleeper.isAlive());
    }

    /** Kindprozess für {@link #stopsChildProcesses()}. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}

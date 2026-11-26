package com.aresstack.spaceharvester.app.ui;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Track work across executor tasks and their queued UI completions. */
final class BackgroundActivity {

    private int pending;

    synchronized Runnable track(Runnable task) {
        pending++;
        return () -> {
            try {
                task.run();
            } finally {
                completed();
            }
        };
    }

    private synchronized void completed() {
        pending--;
        notifyAll();
    }

    synchronized void awaitIdle(Duration timeout) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Still pending background tasks or UI callbacks: " + pending);
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }
}

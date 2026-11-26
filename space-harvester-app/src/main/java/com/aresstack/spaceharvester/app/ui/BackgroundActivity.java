package com.aresstack.spaceharvester.app.ui;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Track work across executor tasks and their queued UI completions.
 *
 * <p>Every tracked task belongs to a {@link #mark() mark}: the one current when it was tracked from outside, or the mark
 * of the tracked task that tracked it (a UI completion, follow-up work started by a completion). Waiting
 * {@link #awaitIdle(long, Duration) since a mark} therefore waits for everything a test started, including follow-ups,
 * and never for work that an earlier test in the same JVM left behind. A timeout names what is still pending: the stack
 * of each running task, or that a task has not started yet (for example a UI callback still queued on the EDT).
 */
final class BackgroundActivity {

    /** The mark of the tracked task running on this thread, inherited by what it tracks. */
    private final ThreadLocal<Long> current = new ThreadLocal<>();
    /** Pending tasks per mark. */
    private final TreeMap<Long, Integer> pending = new TreeMap<>();
    /** Threads currently running a tracked task, with its mark; the rest of {@link #pending} has not started. */
    private final Map<Thread, Long> running = new HashMap<>();
    private long mark;

    /** Begin a new mark: work tracked from outside a tracked task from now on belongs to it. */
    synchronized long mark() {
        return ++mark;
    }

    synchronized Runnable track(Runnable task) {
        Long inherited = current.get();
        long owner = inherited != null ? inherited : mark;
        pending.merge(owner, 1, Integer::sum);
        return () -> {
            Long outer = current.get();
            started(owner);
            current.set(owner);
            try {
                task.run();
            } finally {
                if (outer == null) {
                    current.remove();
                } else {
                    current.set(outer);
                }
                completed(owner, outer);
            }
        };
    }

    private synchronized void started(long owner) {
        running.put(Thread.currentThread(), owner);
    }

    private synchronized void completed(long owner, Long outer) {
        if (outer == null) {
            running.remove(Thread.currentThread());
        } else {
            running.put(Thread.currentThread(), outer);
        }
        pending.computeIfPresent(owner, (key, count) -> count == 1 ? null : count - 1);
        notifyAll();
    }

    /** Wait until all tracked work, whoever started it, has completed. */
    synchronized void awaitIdle(Duration timeout) throws InterruptedException, TimeoutException {
        awaitIdle(0, timeout);
    }

    /** Wait until the work of {@code since} and later marks, including its follow-ups, has completed. */
    synchronized void awaitIdle(long since, Duration timeout) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pendingSince(since) != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Still pending background tasks or UI callbacks: " + pendingSince(since)
                        + describeRunning(since));
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    private int pendingSince(long since) {
        return pending.tailMap(since, true).values().stream().mapToInt(Integer::intValue).sum();
    }

    private String describeRunning(long since) {
        StringBuilder text = new StringBuilder();
        var threads = running.entrySet().stream().filter(entry -> entry.getValue() >= since).map(Map.Entry::getKey)
                .toList();
        text.append(" (running: ").append(threads.size()).append(", not started: ")
                .append(pendingSince(since) - threads.size()).append(')');
        for (Thread thread : threads) {
            text.append(System.lineSeparator()).append("  ").append(thread);
            for (StackTraceElement frame : thread.getStackTrace()) {
                text.append(System.lineSeparator()).append("    at ").append(frame);
            }
        }
        return text.toString();
    }
}

package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * One worker browser process of one chat, behind its OWN owner thread — the single-owner model of
 * {@link BrowserSessionActor}, one level up: the backend (Playwright, browser, every user context and tab of
 * this process) is created on the owner thread and only ever touched there; callers enqueue a task and block
 * on its future, bounded by the per-call timeout. Commands of the user contexts hosted here run one after the
 * other (Playwright Java executes one call at a time per instance); commands of DIFFERENT lanes run in
 * parallel. While idle the owner pumps the browser's events so route interception and popups keep working.
 *
 * <p>A call that exceeds its timeout TERMINATES the lane (off the owner thread, which is the stuck one): the
 * stuck call fails, every user context of this lane is lost and the pool replaces the lane on demand. A lost
 * lane never hangs a worker and never outlives its pool.</p>
 */
final class WorkerBrowserLane {

    /** Leads every error that means "this user context is gone" (lane terminated, browser lost, closed). */
    static final String LOST_MARKER = "USER_CONTEXT_LOST";

    /** A unit of work on the owner thread against the backend. */
    interface LaneTask<T> {
        T run(WorkerBrowserBackend backend) throws BrowserException;
    }

    private static final long PUMP_SLICE_MILLIS = 50L;

    private final String laneId;
    private final String chatKey;
    private final BlockingQueue<Command<?>> queue = new LinkedBlockingQueue<Command<?>>();
    private final CompletableFuture<WorkerBrowserBackend> started = new CompletableFuture<WorkerBrowserBackend>();
    /** Completes once the browser process is gone (closed, never started, or terminated). */
    private final CompletableFuture<Void> stopped = new CompletableFuture<Void>();
    private final BooleanSupplier wake = new BooleanSupplier() {
        public boolean getAsBoolean() {
            return !queue.isEmpty() || !running;
        }
    };
    private volatile Thread ownerThread;
    private volatile WorkerBrowserBackend backend;
    private volatile boolean running = true;
    private volatile boolean terminated;
    private volatile String lostReason;

    WorkerBrowserLane(String laneId, String chatKey) {
        this.laneId = laneId;
        this.chatKey = chatKey;
    }

    String laneId() {
        return laneId;
    }

    String chatKey() {
        return chatKey;
    }

    /** Spawn the owner thread and launch the backend ON it. Returns at once; {@link #awaitStarted} waits. */
    void start(final WorkerBrowserBackend.Factory factory) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                WorkerBrowserBackend launched;
                try {
                    launched = factory.launch();
                } catch (BrowserException | RuntimeException failed) {
                    running = false;
                    lostReason = failed.getMessage();
                    started.completeExceptionally(failed);
                    stopped.complete(null);
                    return;
                }
                backend = launched;
                started.complete(launched);
                runLoop(launched);
            }
        }, "worker-browser-" + laneId);
        thread.setDaemon(true);
        ownerThread = thread;
        thread.start();
    }

    /** Wait until the browser is up (the first Camoufox start may download it — unbounded on purpose). */
    void awaitStarted() throws BrowserException, InterruptedException {
        try {
            started.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw cause instanceof BrowserException ? (BrowserException) cause
                    : new BrowserException("Worker browser start failed: " + cause);
        }
    }

    private void runLoop(WorkerBrowserBackend launched) {
        try {
            while (running) {
                Command<?> command = queue.poll();
                if (command != null) {
                    command.execute(launched);
                    continue;
                }
                if (!launched.isConnected()) {
                    lostReason = "the worker browser process ended";
                    running = false;
                    break;
                }
                if (!launched.pumpEvents(wake, PUMP_SLICE_MILLIS)) {
                    try {
                        command = queue.poll(PUMP_SLICE_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (command != null) {
                        command.execute(launched);
                    }
                }
            }
        } finally {
            if (!terminated) {
                // Orderly, also after a crashed browser: every user context, the browser, the driver child.
                launched.close();
            }
            Command<?> leftover;
            while ((leftover = queue.poll()) != null) {
                leftover.fail(lostException());
            }
            stopped.complete(null);
        }
    }

    /** Completes when the browser process of this lane is gone; the pool counts the lane until then. */
    CompletableFuture<Void> stopped() {
        return stopped;
    }

    /**
     * Run {@code task} on the owner thread. @param timeoutMillis bound of the call (0 = unbounded); when it
     * runs out the lane is terminated and the call fails with {@link #LOST_MARKER}.
     */
    <T> T call(LaneTask<T> task, long timeoutMillis) throws BrowserException, InterruptedException {
        if (Thread.currentThread() == ownerThread) {
            return task.run(backend);
        }
        if (!running) {
            throw lostException();
        }
        Command<T> command = new Command<T>(task);
        queue.add(command);
        if (!running) {
            command.fail(lostException()); // raced the shutdown: the drain may already be over
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            return timeoutMillis > 0 ? command.result.get(timeoutMillis, TimeUnit.MILLISECONDS)
                    : command.result.get();
        } catch (InterruptedException cancelled) {
            // The worker was cancelled, its command still runs on the owner. The call's deadline must hold
            // anyway: a stuck call would otherwise block every teardown queued behind it forever.
            if (timeoutMillis > 0) {
                watchAfterCancel(command, deadline);
            }
            throw cancelled;
        } catch (TimeoutException stuck) {
            terminate("a browser call did not finish within " + timeoutMillis + " ms");
            throw lostException();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BrowserException) {
                throw (BrowserException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new BrowserException("Worker browser call failed: " + cause);
        }
    }

    private void watchAfterCancel(final Command<?> command, final long deadline) {
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                try {
                    command.result.get(Math.max(1L, left), TimeUnit.MILLISECONDS);
                } catch (TimeoutException stuck) {
                    terminate("a cancelled worker's browser call did not finish within its timeout");
                } catch (InterruptedException | ExecutionException | RuntimeException done) {
                    // finished (or failed) in time: nothing to do
                }
            }
        }, "worker-browser-" + laneId + "-cancel-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Fire-and-forget task on the owner (teardown of one user context); dropped when the lane is gone. */
    void submit(LaneTask<?> task) {
        if (running) {
            queue.add(new Command<Object>(castTask(task)));
        }
    }

    @SuppressWarnings("unchecked")
    private static LaneTask<Object> castTask(LaneTask<?> task) {
        return (LaneTask<Object>) task;
    }

    boolean isAlive() {
        return running;
    }

    /** Why the lane is gone, or null while it runs. */
    String lostReason() {
        return lostReason;
    }

    /** Orderly stop: queued commands run first, then the backend closes on the owner thread. */
    void close() {
        submit(new LaneTask<Object>() {
            public Object run(WorkerBrowserBackend b) {
                running = false;
                return null;
            }
        });
    }

    /** Emergency stop from any thread: never blocks on the owner; queued and stuck calls fail. */
    void terminate(String reason) {
        if (lostReason == null) {
            lostReason = reason;
        }
        terminated = true;
        running = false;
        Command<?> leftover;
        while ((leftover = queue.poll()) != null) {
            leftover.fail(lostException());
        }
        WorkerBrowserBackend current = backend;
        if (current != null) {
            current.terminate();
        }
        stopped.complete(null);
        System.err.println("[browser-workers] lane " + laneId + " of chat " + chatKey + " terminated: " + reason);
    }

    private BrowserException lostException() {
        return new BrowserException(LOST_MARKER + " — worker browser " + laneId + " is gone"
                + (lostReason == null ? "." : ": " + lostReason));
    }

    private static final class Command<T> {
        final LaneTask<T> task;
        final CompletableFuture<T> result = new CompletableFuture<T>();

        Command(LaneTask<T> task) {
            this.task = task;
        }

        void execute(WorkerBrowserBackend backend) {
            try {
                result.complete(task.run(backend));
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        }

        void fail(BrowserException reason) {
            result.completeExceptionally(reason);
        }
    }
}

package com.aresstack.spaceharvester.app.ui;

import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Führt Arbeit mit der Core-Bibliothek (Netz, Lucene, Dateien) abseits des Event Dispatch Thread aus und liefert
 * Ergebnis oder Fehler auf dem EDT zurück. Jede Aktion der Oberfläche läuft hierüber, damit große Spaces und
 * langsame Quellen die Oberfläche nie blockieren.
 */
public final class Background {

    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ui-task-", 0).factory());

    private static final Object IDLE_MONITOR = new Object();
    private static int pending;

    private Background() {
    }

    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        start(work, onSuccess, onFailure);
    }

    /**
     * Wie {@link #run}; die Rückgabe bricht die Arbeit ab ({@link Future#cancel(boolean) cancel(true)} unterbricht
     * ihren Thread). Eine abgebrochene Arbeit meldet sich, wenn überhaupt, als Fehler.
     */
    public static <T> Future<?> start(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            try {
                T result = work.call();
                deliver(() -> onSuccess.accept(result));
            } catch (Throwable e) {
                deliver(() -> onFailure.accept(e));
            }
            return null;
        });
        executeTracked(task);
        return task;
    }

    /**
     * Der Executor selbst, für Arbeit, die ihr Ergebnis selbst auf den EDT bringt und darum austauschbar bleiben soll
     * (etwa in Tests von Hand ausgeführt).
     */
    public static java.util.concurrent.Executor executor() {
        return Background::executeTracked;
    }

    private static void executeTracked(Runnable task) {
        started();
        try {
            EXECUTOR.execute(() -> {
                try {
                    task.run();
                } finally {
                    finished();
                }
            });
        } catch (RuntimeException failure) {
            finished();
            throw failure;
        }
    }

    private static void deliver(Runnable callback) {
        started();
        SwingUtilities.invokeLater(() -> {
            try {
                callback.run();
            } finally {
                finished();
            }
        });
    }

    private static void started() {
        synchronized (IDLE_MONITOR) {
            pending++;
        }
    }

    private static void finished() {
        synchronized (IDLE_MONITOR) {
            pending--;
            IDLE_MONITOR.notifyAll();
        }
    }

    /** Wait for actual worker completion and EDT delivery, including follow-up work. */
    static void awaitIdle(java.time.Duration timeout) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Cannot await background tasks on the EDT");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (IDLE_MONITOR) {
            while (pending != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("Background tasks still running: " + pending);
                }
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(IDLE_MONITOR, remaining);
            }
        }
    }

    /** Wie {@link #run}, für Arbeit ohne Ergebnis. */
    public static void execute(ThrowingRunnable work, Runnable onSuccess, Consumer<Throwable> onFailure) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onFailure);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}

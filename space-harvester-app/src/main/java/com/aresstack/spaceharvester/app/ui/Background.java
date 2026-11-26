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

    private static final BackgroundActivity ACTIVITY = new BackgroundActivity();
    private static final java.util.concurrent.ThreadFactory THREADS = Thread.ofVirtual().name("ui-task-", 0).factory();
    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
            work -> THREADS.newThread(ACTIVITY.track(work)));

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
        return EXECUTOR.submit(() -> {
            try {
                T result = work.call();
                SwingUtilities.invokeLater(ACTIVITY.track(() -> onSuccess.accept(result)));
            } catch (Throwable e) {
                SwingUtilities.invokeLater(ACTIVITY.track(() -> onFailure.accept(e)));
            }
        });
    }

    /**
     * Der Executor selbst, für Arbeit, die ihr Ergebnis selbst auf den EDT bringt und darum austauschbar bleiben soll
     * (etwa in Tests von Hand ausgeführt).
     */
    public static java.util.concurrent.Executor executor() {
        return EXECUTOR;
    }

    /** Wie {@link #run}, für Arbeit ohne Ergebnis. */
    public static void execute(ThrowingRunnable work, Runnable onSuccess, Consumer<Throwable> onFailure) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onFailure);
    }

    /** Await background tasks and their completion callbacks before closing test fixtures. */
    static void awaitIdle(java.time.Duration timeout) throws InterruptedException, java.util.concurrent.TimeoutException {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Await background activity outside the event dispatch thread");
        }
        ACTIVITY.awaitIdle(timeout);
    }

    /**
     * Begin a new mark for {@link #awaitIdle(long, java.time.Duration)}: work started from now on (and its completions
     * and follow-ups) belongs to it, work still running from earlier does not.
     */
    static long mark() {
        return ACTIVITY.mark();
    }

    /** Await the background tasks and completion callbacks started since {@code mark}, before closing test fixtures. */
    static void awaitIdle(long mark, java.time.Duration timeout)
            throws InterruptedException, java.util.concurrent.TimeoutException {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Await background activity outside the event dispatch thread");
        }
        ACTIVITY.awaitIdle(mark, timeout);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}

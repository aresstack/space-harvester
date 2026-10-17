package com.aresstack.spaceharvester.app.ui;

import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Führt Arbeit mit der Core-Bibliothek (Netz, Lucene, Dateien) abseits des Event Dispatch Thread aus und liefert
 * Ergebnis oder Fehler auf dem EDT zurück. Jede Aktion der Oberfläche läuft hierüber, damit große Spaces und
 * langsame Quellen die Oberfläche nie blockieren.
 */
public final class Background {

    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ui-task-", 0).factory());

    private Background() {
    }

    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        EXECUTOR.execute(() -> {
            try {
                T result = work.call();
                SwingUtilities.invokeLater(() -> onSuccess.accept(result));
            } catch (Throwable e) {
                SwingUtilities.invokeLater(() -> onFailure.accept(e));
            }
        });
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

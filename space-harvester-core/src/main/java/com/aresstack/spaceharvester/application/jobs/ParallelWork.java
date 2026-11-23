package com.aresstack.spaceharvester.application.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Arbeit innerhalb eines Auftragsschritts parallel, höchstens so viele zugleich wie eingestellt (etwa
 * {@code ai.parallel}), Ergebnisse in der Reihenfolge der Einträge. Nach Abbruch beginnt kein neuer Eintrag; der erste
 * Fehler beendet den Lauf und bricht die übrigen ab.
 */
public final class ParallelWork {

    private ParallelWork() {
    }

    /** Im Auftrag: {@link JobContext#checkpoint()} vor jedem Eintrag, Unterbrechung = {@link JobCancelledException}. */
    public static <T, R> List<R> map(int lanes, List<T> items, JobContext context, String threadName,
                                     Function<T, R> work) {
        Objects.requireNonNull(context, "context");
        return map(lanes, items, threadName, context::checkpoint, () -> new JobCancelledException(context.jobId()),
                work);
    }

    /**
     * @param lanes      höchstens so viele zugleich; 1 oder weniger heißt nacheinander auf dem aufrufenden Thread
     * @param threadName Präfix der Threads
     * @param checkpoint vor jedem Eintrag; wirft, wenn abgebrochen ist
     * @param cancelled  die Ausnahme, wenn der aufrufende Thread beim Warten unterbrochen wird
     */
    public static <T, R> List<R> map(int lanes, List<T> items, String threadName, Runnable checkpoint,
                                     Supplier<? extends RuntimeException> cancelled, Function<T, R> work) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(work, "work");
        int parallel = Math.max(1, Math.min(lanes, items.size()));
        if (parallel == 1) {
            List<R> results = new ArrayList<>(items.size());
            for (T item : items) {
                checkpoint.run();
                results.add(work.apply(item));
            }
            return results;
        }
        ExecutorService pool = Executors.newFixedThreadPool(parallel,
                Thread.ofPlatform().daemon().name(threadName, 0).factory());
        try {
            List<Future<R>> running = new ArrayList<>(items.size());
            for (T item : items) {
                running.add(pool.submit(() -> {
                    checkpoint.run();
                    return work.apply(item);
                }));
            }
            List<R> results = new ArrayList<>(items.size());
            for (Future<R> future : running) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    running.forEach(other -> other.cancel(true));
                    if (e.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (e.getCause() instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.forEach(other -> other.cancel(true));
                    throw cancelled.get();
                }
            }
            checkpoint.run();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

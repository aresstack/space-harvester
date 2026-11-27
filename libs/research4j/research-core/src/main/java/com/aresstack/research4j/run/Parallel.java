package com.aresstack.research4j.run;

import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.run.CancelledException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Runs one step over a list with as many calls at the same time as the host allows: one after the other at 1, side by
 * side above. The results keep the order of the input, so the outcome does not depend on the parallelism.
 */
public final class Parallel {

    private Parallel() {
    }

    public static <T, R> List<R> map(List<T> items, int parallel, CancellationSignal cancel, Function<T, R> work) {
        List<R> out = new ArrayList<>(items.size());
        if (items.isEmpty()) {
            return out;
        }
        CancellationSignal signal = cancel == null ? CancellationSignal.NEVER : cancel;
        int threads = Math.max(1, Math.min(parallel, items.size()));
        if (threads == 1) {
            for (T item : items) {
                signal.throwIfCancelled();
                out.add(work.apply(item));
            }
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "research4j-step");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<R>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(pool.submit(() -> {
                    signal.throwIfCancelled();
                    return work.apply(item);
                }));
            }
            for (Future<R> future : futures) {
                out.add(future.get());
            }
            return out;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } finally {
            pool.shutdownNow();
        }
    }
}

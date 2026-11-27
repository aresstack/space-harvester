package com.aresstack.knowledge4j.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Runs the pieces of one stage of long work, such as one source profile per source. The library hands over the work
 * of each piece; the host decides how many run at a time and where: in the run's own job, or each piece as a job of
 * its own beneath it (a bracket with sub-jobs), so the user sees which piece runs. Results come back in the order of
 * the items; a piece that throws ends the stage with that exception. A title names a piece for the host's job list
 * only; it is never logged by the library.
 */
public interface Pieces {

    /**
     * @param stage the stage the pieces belong to, as the library names it in its progress (e.g. a step name)
     * @param title the name of a piece for the user, e.g. a source's title
     * @param work  the piece; it may block, call a model, and throw
     * @return one result per item, in the order of the items
     */
    <T, R> List<R> run(String stage, List<T> items, Function<T, String> title, Function<T, R> work);

    /** Every piece on a pool of {@code capacity} threads (at least 1), all in the caller's job. */
    static Pieces parallel(final int capacity) {
        return new Pieces() {
            @Override
            public <T, R> List<R> run(String stage, List<T> items, Function<T, String> title, Function<T, R> work) {
                if (items == null || items.isEmpty()) {
                    return Collections.emptyList();
                }
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(capacity, items.size())));
                List<Future<R>> running = new ArrayList<Future<R>>(items.size());
                try {
                    for (final T item : items) {
                        running.add(pool.submit(() -> work.apply(item)));
                    }
                    List<R> out = new ArrayList<R>(items.size());
                    for (Future<R> piece : running) {
                        out.add(piece.get());
                    }
                    return out;
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof RuntimeException) {
                        throw (RuntimeException) failed.getCause();
                    }
                    if (failed.getCause() instanceof Error) {
                        throw (Error) failed.getCause();
                    }
                    throw new IllegalStateException(stage + " failed", failed.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                } finally {
                    pool.shutdownNow();
                }
            }
        };
    }
}

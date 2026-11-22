package com.aresstack.spaceharvester.application.podcast;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feste Zahl von Fäden ({@code ai.parallel}), die immer die Arbeit mit dem kleinsten Rang zuerst nehmen; bei gleichem
 * Rang gilt die Reihenfolge des Einreichens. Der Podcast reicht so alles auf einmal ein und bekommt trotzdem zuerst,
 * was die Wiedergabe als Nächstes braucht: Thema 1 vor Thema 2, Text vor Bild.
 */
final class RankedPool implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final AtomicLong sequence = new AtomicLong();

    RankedPool(int threads, String name) {
        Objects.requireNonNull(name, "name");
        int size = Math.max(1, threads);
        AtomicLong counter = new AtomicLong();
        this.executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Führt {@code work} mit diesem Rang aus; das Ergebnis oder der Fehler landet in der Zukunft. */
    <T> CompletableFuture<T> supply(long rank, Callable<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        execute(rank, () -> {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Führt {@code work} mit diesem Rang aus. */
    void execute(long rank, Runnable work) {
        executor.execute(new Ranked(rank, sequence.getAndIncrement(), Objects.requireNonNull(work, "work")));
    }

    /** Bricht alles ab: Laufendes wird unterbrochen, Wartendes verworfen. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Ranked(long rank, long order, Runnable work) implements Runnable, Comparable<Ranked> {
        @Override
        public void run() {
            work.run();
        }

        @Override
        public int compareTo(Ranked other) {
            int byRank = Long.compare(rank, other.rank);
            return byRank != 0 ? byRank : Long.compare(order, other.order);
        }
    }
}

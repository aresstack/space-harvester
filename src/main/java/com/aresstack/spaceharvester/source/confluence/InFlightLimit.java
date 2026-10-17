package com.aresstack.spaceharvester.source.confluence;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Begrenzt die gleichzeitig laufenden asynchronen Snapshot-Abrufe einschließlich ihrer JSON-Abbildung (#47). Ein Abruf
 * belegt seinen Platz vom Start der Anfrage bis zum Ende seiner Stufe; weitere warten als noch nicht gestartete
 * Anfragen, also ohne gepufferte Antwort. So hängt der Speicherbedarf an {@code limit × maxResponseBytes} und nicht
 * daran, wie schnell Antworten schneller eintreffen als die Abbildung sie verarbeitet.
 */
public final class InFlightLimit {

    private final int limit;
    private final AtomicInteger running = new AtomicInteger();
    private final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();
    /** Synchron endende Abrufe rufen {@link #drain()} erneut auf; die äußere Schleife übernimmt, statt zu schachteln. */
    private final ThreadLocal<Boolean> draining = ThreadLocal.withInitial(() -> false);

    /** @param limit gleichzeitig laufende Abrufe, mindestens 1 */
    public InFlightLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    /** Laufende Abrufe (für Tests und Diagnose). */
    public int running() {
        return running.get();
    }

    /**
     * Startet den Abruf, sobald ein Platz frei ist. Die Stufe endet wie die des Abrufs; wird sie abgebrochen, bevor
     * der Abruf startet, startet er nie, sonst wird der Abbruch an ihn weitergegeben.
     */
    public <T> CompletableFuture<T> submit(Supplier<? extends CompletableFuture<T>> start) {
        CompletableFuture<T> result = new CompletableFuture<>();
        waiting.add(() -> run(start, result));
        drain();
        return result;
    }

    private <T> void run(Supplier<? extends CompletableFuture<T>> start, CompletableFuture<T> result) {
        if (result.isDone()) {
            release();
            return;
        }
        CompletableFuture<T> inner;
        try {
            inner = start.get();
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
            release();
            return;
        }
        inner.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
            release();
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                inner.cancel(true);
            }
        });
    }

    private void release() {
        running.decrementAndGet();
        drain();
    }

    private void drain() {
        if (draining.get()) {
            return;
        }
        draining.set(true);
        try {
            drainLoop();
        } finally {
            draining.set(false);
        }
    }

    private void drainLoop() {
        while (!waiting.isEmpty()) {
            int now = running.get();
            if (now >= limit) {
                return;
            }
            if (!running.compareAndSet(now, now + 1)) {
                continue;
            }
            Runnable next = waiting.poll();
            if (next == null) {
                running.decrementAndGet();
                // Ein anderer Thread hat die Warteschlange inzwischen geleert; erneut prüfen.
                continue;
            }
            next.run();
        }
    }
}

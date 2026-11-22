package com.aresstack.spaceharvester.application.enrichment;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Die gerade laufenden Hintergrundteile einer Komponente. Ihre Arbeit läuft auf den Threads der Auftragsverwaltung,
 * die erst nach der Ablage schließt; {@code close()} der Komponente wartet deshalb hier, bis die Teile ihren Schritt
 * beendet haben, statt nur auf den eigenen Thread (Codex-Befund #1073).
 */
final class ActiveParts {

    /**
     * {@link ReentrantLock} statt {@code synchronized} (Architekt 22.11.2026): Wer in {@link #await} wartet, gibt als
     * virtueller Thread seinen Träger frei.
     */
    private final ReentrantLock lock = new ReentrantLock();
    /** Ein Teil hat seinen Schritt beendet. */
    private final Condition left = lock.newCondition();
    private int active;

    /**
     * Führt die Arbeit eines Teils aus. Ist die Komponente schon geschlossen, beginnt sie nicht: Erst eintragen, dann
     * nachsehen, so wartet ein gleichzeitiges Schließen auf sie oder sie sieht es.
     *
     * @param closed ob die Komponente geschlossen ist
     * @param ifClosed was dann geschieht, etwa die Unterbrechung des Teils
     */
    void run(Supplier<Boolean> closed, Runnable ifClosed, Runnable work) {
        enter();
        try {
            if (closed.get()) {
                ifClosed.run();
                return;
            }
            work.run();
        } finally {
            leave();
        }
    }

    private void enter() {
        lock.lock();
        try {
            active++;
        } finally {
            lock.unlock();
        }
    }

    private void leave() {
        lock.lock();
        try {
            active--;
            left.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Wartet höchstens {@code wait} lang, bis kein Teil mehr läuft; {@code false}, wenn noch einer läuft. */
    boolean await(Duration wait) throws InterruptedException {
        long remaining = wait.toNanos();
        lock.lock();
        try {
            while (active > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = left.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}

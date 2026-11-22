package com.aresstack.spaceharvester.application.enrichment;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Die gerade laufenden Hintergrundteile einer Komponente. Ihre Arbeit läuft auf den Threads der Auftragsverwaltung,
 * die erst nach der Ablage schließt; {@code close()} der Komponente wartet deshalb hier, bis die Teile ihren Schritt
 * beendet haben, statt nur auf den eigenen Thread (Codex-Befund #1073).
 */
final class ActiveParts {

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

    private synchronized void enter() {
        active++;
    }

    private synchronized void leave() {
        active--;
        notifyAll();
    }

    /** Wartet höchstens {@code wait} lang, bis kein Teil mehr läuft; {@code false}, wenn noch einer läuft. */
    synchronized boolean await(Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (active > 0) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }
}

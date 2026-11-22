package com.aresstack.spaceharvester.application.images;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Vorrang der Bildbeschreibungen, die der Benutzer selbst anstößt (Reiter „Bilder“), vor dem Hintergrundlauf
 * „Bilder beschreiben“ (Angelo 21.11.2026: „eher nachrangig“). Solange eine solche Beschreibung läuft, wartet der
 * Hintergrund vor seinem nächsten Bild an einer Bedingung, die das Ende der letzten interaktiven Beschreibung freigibt,
 * auch wenn sie scheitert. Kein Takt, kein Zeitgeber (ADR 0027): Abbruch, Anhalten und Abkoppeln wecken über
 * {@link JobContext#whenStopRequested}.
 */
public final class ModelPriority {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int interactive;

    /**
     * Meldet eine interaktive Beschreibung an; {@link Interactive#close()} meldet sie ab, auch im Fehlerfall über
     * try-with-resources.
     */
    public Interactive interactive() {
        lock.lock();
        try {
            interactive++;
        } finally {
            lock.unlock();
        }
        return new Interactive();
    }

    /** Ob gerade eine interaktive Beschreibung läuft. */
    public boolean busy() {
        lock.lock();
        try {
            return interactive > 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wartet, bis keine interaktive Beschreibung mehr läuft. Kehrt sofort zurück, wenn keine läuft.
     *
     * @param context der Hintergrundauftrag; Abbruch oder Anhalten beenden das Warten
     * @param waiting läuft einmal, bevor gewartet wird, etwa um die Phase zu nennen
     * @return ob gewartet wurde
     * @throws com.aresstack.spaceharvester.application.jobs.JobCancelledException wenn währenddessen Abbruch oder
     *                                                                             Anhalten angefordert wird
     */
    public boolean awaitIdle(JobContext context, Runnable waiting) {
        if (!busy()) {
            return false;
        }
        waiting.run();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        AutoCloseable registration = context.whenStopRequested(() -> {
            stop.set(true);
            lock.lock();
            try {
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        });
        try {
            lock.lock();
            try {
                while (interactive > 0 && !stop.get()) {
                    idle.awaitUninterruptibly();
                }
            } finally {
                lock.unlock();
            }
        } finally {
            try {
                registration.close();
            } catch (Exception e) {
                // Abmelden kann nicht scheitern; nichts zu tun.
            }
        }
        // Außerhalb der Sperre: Der Prüfpunkt wirft bei Abbruch, Anhalten oder abgelaufener Laufzeitgrenze.
        context.checkpoint();
        return true;
    }

    /** Eine laufende interaktive Beschreibung. */
    public final class Interactive implements AutoCloseable {

        private boolean closed;

        private Interactive() {
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (!closed) {
                    closed = true;
                    interactive--;
                    if (interactive == 0) {
                        idle.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }
}

package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.application.podcast.ReadAloud;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Verbindet den Vorlese-Knopf der Leseansicht mit dem Vorlese-Ablauf: vorgelesen wird in einem eigenen Thread (nie auf
 * dem EDT), immer nur ein Text; ein neuer (etwa nach einem Doppelklick auf ein Wort) bricht den laufenden sofort ab,
 * auch wenn dieser noch auf ein Stück des Dienstes wartet, statt hinter ihm anzustehen.
 *
 * <p>Aus dem Enterprise AI Client ({@code proasteion/exedra/speech/ReadAloudBinding}) übernommen (Angelo 23.11.2026):
 * derselbe Ablauf mit Generation, Abbruch per Interrupt und genau einer Endemeldung je Ausgabe. Anders als dort entsteht
 * der Vorlese-Ablauf erst beim ersten Vorlesen auf dem Lese-Thread (er fragt den Dienst nach seinen Sprachmodellen),
 * und ein Fehler geht in die Statuszeile statt in einen Dialog.
 */
public final class ReadAloudBinding {

    private static final System.Logger LOG = System.getLogger(ReadAloudBinding.class.getName());

    private final Supplier<ReadAloud> factory;
    private final Consumer<String> failures;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "space-harvester-read-aloud");
        thread.setDaemon(true);
        return thread;
    });
    /** Jede Ausgabe und jedes Stopp zählt hoch; eine wartende Ausgabe mit alter Nummer spricht nicht mehr. */
    private final AtomicLong generation = new AtomicLong();
    /** Der Vorlese-Ablauf, sobald er entstanden ist. */
    private volatile ReadAloud service;
    /** Pausiert, bevor der Ablauf da war: er beginnt angehalten. */
    private volatile boolean pausedEarly;
    /** Die laufende oder wartende Ausgabe; {@link #speak}, {@link #stop()} und {@link #close()} brechen sie ab. */
    private Read active;

    /**
     * @param factory  baut den Vorlese-Ablauf (fragt den Dienst; läuft auf dem Lese-Thread)
     * @param failures bekommt auf dem EDT den Grund, wenn ein Vorlesen scheitert (einmal je Vorlesen)
     */
    public ReadAloudBinding(Supplier<ReadAloud> factory, Consumer<String> failures) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /**
     * Liest {@code text} vor; eine laufende Ausgabe endet vorher. Kehrt sofort zurück und meldet danach auf dem EDT,
     * dass die Ausgabe vorbei ist (fertig gesprochen, fehlgeschlagen, gestoppt oder abgelöst).
     */
    public void speak(String text, Runnable finished) {
        ReadAloud current = service;
        if (current != null) {
            current.stop(); // beendet eine laufende Ausgabe
        }
        pausedEarly = false;
        long mine = generation.incrementAndGet();
        Read read = new Read(finished);
        synchronized (this) {
            cancelActive();
            active = read;
            read.future = reader.submit(() -> {
                try {
                    read(mine, text);
                } finally {
                    read.finish();
                }
            });
        }
    }

    /** Bricht die aktive Ausgabe ab: ein Warten auf ein Stück endet per Interrupt, ihr Ende wird gemeldet. */
    private synchronized void cancelActive() {
        Read previous = active;
        active = null;
        if (previous != null) {
            previous.cancel();
        }
    }

    /** Meldet das Ende einer Ausgabe auf dem EDT. */
    private static void done(Runnable finished) {
        if (finished != null) {
            SwingUtilities.invokeLater(finished);
        }
    }

    /** Eine Ausgabe mit ihrem Abschluss; das Ende meldet sie genau einmal, ob fertig gelesen oder abgebrochen. */
    private static final class Read {

        private final Runnable finished;
        private final AtomicBoolean reported = new AtomicBoolean();
        private Future<?> future;

        Read(Runnable finished) {
            this.finished = finished;
        }

        void cancel() {
            if (future != null) {
                future.cancel(true);
            }
            finish(); // auch eine nie gestartete Ausgabe meldet ihr Ende
        }

        void finish() {
            if (reported.compareAndSet(false, true)) {
                done(finished);
            }
        }
    }

    private void read(long mine, String text) {
        if (generation.get() != mine) {
            return; // inzwischen gestoppt oder von einem neueren Text abgelöst
        }
        boolean[] reported = new boolean[1]; // eine Meldung je Vorlesen, nicht je Abschnitt
        try {
            ReadAloud current = service();
            if (generation.get() != mine) {
                return;
            }
            if (pausedEarly) {
                current.pause();
            }
            current.speak(text, new ReadAloud.Listener() {
                @Override
                public void failed(String message) {
                    LOG.log(System.Logger.Level.WARNING, "Vorlesen: " + message);
                    if (!reported[0]) {
                        reported[0] = true;
                        showFailure(message);
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Vorlesen fehlgeschlagen ({0})", e.getClass().getName());
            if (!reported[0] && generation.get() == mine) {
                showFailure(e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                        : e.getMessage());
            }
        }
    }

    /** Je Vorlesen neu gebaut: ein inzwischen gewähltes Modell oder ein anderer Dienst gilt sofort. */
    private ReadAloud service() {
        ReadAloud current = Objects.requireNonNull(factory.get(), "read aloud");
        service = current;
        return current;
    }

    /** Beendet die laufende Ausgabe; idempotent. */
    public void stop() {
        generation.incrementAndGet();
        pausedEarly = false;
        ReadAloud current = service;
        if (current != null) {
            current.stop();
        }
        cancelActive();
    }

    /** Hält die laufende Ausgabe an der Stelle an; {@link #stop()} oder ein neuer Text verwirft sie. */
    public void pause() {
        ReadAloud current = service;
        if (current != null) {
            current.pause();
        } else {
            pausedEarly = true;
        }
    }

    /** Setzt eine angehaltene Ausgabe dort fort, wo sie stand. */
    public void resume() {
        pausedEarly = false;
        ReadAloud current = service;
        if (current != null) {
            current.resume();
        }
    }

    public boolean isPaused() {
        ReadAloud current = service;
        return current == null ? pausedEarly : current.isPaused();
    }

    /** Ein Fehler beim Vorlesen verpufft nicht still: der Grund geht auf dem EDT an die Statuszeile. */
    private void showFailure(String message) {
        SwingUtilities.invokeLater(() -> failures.accept(message));
    }

    /** Beendet das Vorlesen beim Schließen; idempotent. */
    public void close() {
        stop();
        reader.shutdownNow();
    }
}

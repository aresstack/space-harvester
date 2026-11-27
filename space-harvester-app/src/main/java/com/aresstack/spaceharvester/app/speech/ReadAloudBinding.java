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
    /** Der Vorlese-Ablauf des laufenden Vorlesens, sobald er spricht; {@code null} davor. */
    private volatile ReadAloud service;
    /** Angehalten, bevor der Ablauf des laufenden Vorlesens da war: er beginnt erst beim Fortsetzen. */
    private volatile boolean pausedEarly;
    private final Object pauseLock = new Object();
    /** Sprechgeschwindigkeit für jedes Vorlesen (Faktor, 1 = normal); gilt auch dem laufenden ab dem nächsten Block. */
    private volatile double rate = 1.0;
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
        speak(text, () -> true, finished);
    }

    /**
     * Wie {@link #speak(String, Runnable)}; {@code allowed} wird auf dem Lese-Thread unmittelbar vor dem Vorlesen
     * gefragt (etwa ob die Seite noch an einen KI-Dienst darf, ADR 0028). Sagt es nein, wird nichts gesprochen und der
     * Grund geht an die Statuszeile.
     */
    public void speak(String text, java.util.function.BooleanSupplier allowed, Runnable finished) {
        speak(text, allowed, finished, null);
    }

    /** Wohin das Vorlesen meldet, welcher Abschnitt gerade erklingt (auf dem EDT, nur für die laufende Ausgabe). */
    public interface Progress {

        /** Ungefähr die Zeichen {@code [from, to)} des vorgelesenen Texts erklingen jetzt, etwa {@code millis} lang. */
        void playing(int from, int to, long millis);
    }

    /**
     * Wie {@link #speak(String, java.util.function.BooleanSupplier, Runnable)}; {@code progress} erfährt auf dem EDT,
     * welcher Abschnitt gerade erklingt (grobe Hervorhebung im Chat, Angelo 23.11.2026), {@code null} = niemand.
     */
    public void speak(String text, java.util.function.BooleanSupplier allowed, Runnable finished,
                      Progress progress) {
        Objects.requireNonNull(allowed, "allowed");
        ReadAloud current = service;
        service = null; // ein Anhalten vor dem Start gilt dem neuen Vorlesen, nicht dem alten
        if (current != null) {
            current.stop(); // beendet eine laufende Ausgabe
        }
        resumeEarly();
        long mine = generation.incrementAndGet();
        Read read = new Read(finished);
        synchronized (this) {
            cancelActive();
            active = read;
            read.future = reader.submit(() -> {
                try {
                    read(mine, text, allowed, progress);
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

    /** Grund in der Statuszeile, wenn die Seite inzwischen nicht mehr an einen KI-Dienst darf. */
    public static final String NOT_ALLOWED = "Die Seite ist nicht mehr für KI-Dienste freigegeben";

    private void read(long mine, String text, java.util.function.BooleanSupplier allowed, Progress progress) {
        if (generation.get() != mine) {
            return; // inzwischen gestoppt oder von einem neueren Text abgelöst
        }
        boolean[] reported = new boolean[1]; // eine Meldung je Vorlesen, nicht je Abschnitt
        try {
            if (!allowed.getAsBoolean()) {
                if (generation.get() == mine) {
                    showFailure(NOT_ALLOWED);
                }
                return;
            }
            ReadAloud current = Objects.requireNonNull(factory.get(), "read aloud");
            // Angehalten, während der Dienst noch nach seinen Modellen gefragt wurde: erst beim Fortsetzen beginnen.
            synchronized (pauseLock) {
                while (pausedEarly && generation.get() == mine) {
                    pauseLock.wait(50);
                }
            }
            if (generation.get() != mine) {
                return;
            }
            current.setRate(rate);
            service = current;
            current.speak(text, new ReadAloud.Listener() {
                @Override
                public void failed(String message) {
                    LOG.log(System.Logger.Level.WARNING, "Vorlesen: " + message);
                    if (!reported[0]) {
                        reported[0] = true;
                        showFailure(message);
                    }
                }

                @Override
                public void playing(int from, int to, long millis) {
                    if (progress != null && generation.get() == mine) {
                        SwingUtilities.invokeLater(() -> {
                            if (generation.get() == mine) {
                                progress.playing(from, to, millis);
                            }
                        });
                    }
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // abgelöst oder gestoppt
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Vorlesen fehlgeschlagen ({0})", e.getClass().getName());
            if (!reported[0] && generation.get() == mine) {
                showFailure(e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                        : e.getMessage());
            }
        }
    }

    /** Beendet die laufende Ausgabe; idempotent. */
    public void stop() {
        generation.incrementAndGet();
        resumeEarly();
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

    private void resumeEarly() {
        synchronized (pauseLock) {
            pausedEarly = false;
            pauseLock.notifyAll();
        }
    }

    /** Setzt eine angehaltene Ausgabe dort fort, wo sie stand. */
    public void resume() {
        resumeEarly();
        ReadAloud current = service;
        if (current != null) {
            current.resume();
        }
    }

    /**
     * Sprechgeschwindigkeit (Faktor, 1 = normal, Tonhöhe bleibt): gilt dem laufenden Vorlesen ab dem nächsten Block und
     * jedem weiteren. Wie im Enterprise AI Client ({@code ReadAloudBinding.setRate}).
     */
    public void setRate(double rate) {
        this.rate = rate;
        ReadAloud current = service;
        if (current != null) {
            current.setRate(rate);
        }
    }

    /** Die eingestellte Sprechgeschwindigkeit. */
    public double rate() {
        return rate;
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

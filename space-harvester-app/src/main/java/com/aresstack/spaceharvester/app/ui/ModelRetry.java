package com.aresstack.spaceharvester.app.ui;

import java.time.Duration;
import java.util.Objects;

/**
 * Wiederholt „Modelle laden“ im Einrichtungsassistenten, bis es gelingt: Wartezeiten 5 s, 10 s, 20 s … höchstens 60 s.
 * Bewusst langsam: Der Core ({@code RemoteAi.models()}) wiederholt einen nicht erreichbaren Dienst schon selbst kurz
 * hintereinander; diese Schleife setzt erst danach an.
 * Ein Fehler, der sich durch Warten nicht behebt (abgelehnter API-Key, fehlende Adresse), beendet die Schleife;
 * {@link #stop()} (Schritt verlassen, Assistent geschlossen) ebenso. {@link #start()} beginnt neu, auch bei laufender
 * Wartezeit, und setzt die Wartezeit zurück. Alles auf dem EDT; die Zeitplanung ist austauschbar, damit Tests ohne
 * echtes Warten auskommen.
 */
final class ModelRetry {

    static final Duration FIRST = Duration.ofSeconds(5);
    static final Duration MAX = Duration.ofSeconds(60);

    /** Plant eine Aufgabe nach {@code delay} ein und liefert, wie man sie wieder absagt. */
    @FunctionalInterface
    interface Scheduler {
        Runnable schedule(Duration delay, Runnable task);
    }

    /** Ein Versuch; meldet sein Ende genau einmal an {@code outcome}, gleich oder später. */
    @FunctionalInterface
    interface Attempt {
        void run(Outcome outcome);
    }

    /** Ende eines Versuchs. */
    interface Outcome {
        void succeeded();

        /** @param retryable ob Warten helfen kann; sonst endet die Schleife */
        void failed(boolean retryable);
    }

    /** Meldet nach einem gescheiterten Versuch dessen Nummer und die Wartezeit bis zum nächsten. */
    @FunctionalInterface
    interface Status {
        void retrying(int attempt, Duration next);
    }

    /** Swing-Timer: die Aufgabe läuft auf dem EDT. */
    static final Scheduler SWING = (delay, task) -> {
        javax.swing.Timer timer = new javax.swing.Timer((int) Math.min(Integer.MAX_VALUE, delay.toMillis()),
                e -> task.run());
        timer.setRepeats(false);
        timer.start();
        return timer::stop;
    };

    private final Scheduler scheduler;
    private final Attempt attempt;
    private final Status status;
    private int attempts;
    /** Zählt bei jedem Start und Stopp weiter; Rückmeldungen älterer Durchläufe gehen ins Leere. */
    private int generation;
    private boolean active;
    private Runnable cancel;

    ModelRetry(Scheduler scheduler, Attempt attempt, Status status) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** Wartezeit nach {@code failed} gescheiterten Versuchen (ab 1): 5 s, 10 s, 20 s … höchstens {@link #MAX}. */
    static Duration delay(int failed) {
        int exponent = Math.max(0, Math.min(failed - 1, 30));
        long seconds = FIRST.toSeconds() << exponent;
        return seconds >= MAX.toSeconds() || seconds <= 0 ? MAX : Duration.ofSeconds(seconds);
    }

    /** Beginnt neu mit einem sofortigen Versuch; eine laufende Wartezeit verfällt. */
    void start() {
        stop();
        active = true;
        attempts = 0;
        next();
    }

    /** Beendet die Schleife; ein noch laufender Versuch plant nichts mehr ein. */
    void stop() {
        active = false;
        generation++;
        if (cancel != null) {
            cancel.run();
            cancel = null;
        }
    }

    /** Ob ein Versuch läuft oder einer eingeplant ist. */
    boolean active() {
        return active;
    }

    /** Die Nummer des letzten Versuchs in diesem Durchlauf. */
    int attempts() {
        return attempts;
    }

    private void next() {
        cancel = null;
        int run = generation;
        attempts++;
        int number = attempts;
        attempt.run(new Outcome() {
            private boolean reported;

            @Override
            public void succeeded() {
                if (report(run)) {
                    active = false;
                }
            }

            @Override
            public void failed(boolean retryable) {
                if (!report(run)) {
                    return;
                }
                if (!retryable) {
                    active = false;
                    return;
                }
                Duration wait = delay(number);
                status.retrying(number, wait);
                cancel = scheduler.schedule(wait, () -> {
                    if (run == generation && active) {
                        next();
                    }
                });
            }

            private boolean report(int of) {
                if (reported) {
                    return false;
                }
                reported = true;
                return of == generation && active;
            }
        });
    }
}

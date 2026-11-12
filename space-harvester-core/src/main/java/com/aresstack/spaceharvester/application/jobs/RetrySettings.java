package com.aresstack.spaceharvester.application.jobs;

import java.time.Duration;
import java.util.Objects;

/**
 * Wiederholungen bei vorübergehenden Fehlern unter {@code space-harvester.jobs.retry} (ADR 0025): wie oft und mit
 * welcher Wartezeit der Abruf einer Quelle nach einem Netzfehler, einer Zeitüberschreitung, 429 oder 5xx wiederholt
 * wird, und nach wie vielen solchen Fehlern nacheinander sich ein Auftrag selbst anhält („Verbindung unterbrochen“).
 *
 * <p>Es gibt nur eine Wiederholungsschleife: die des REST-Clients der Quelle (exponentielle Wartezeit mit Zufall). Nie
 * wiederholt werden 401, 403, 404, ungültige Antworten, Datei-Downloads mit offener Code-Abfrage und das Absenden eines
 * Einmalcodes; dort entstünde sonst eine neue Sitzung mit neuer Mail. Eigene {@code options} einer Quelle
 * ({@code maxRetries}, {@code retryBackoffMillis}, {@code maxRetryBackoffMillis}) gehen vor.
 *
 * @param maxAttempts        weitere Versuche nach dem ersten, 0 (aus) bis {@value #MAX_ATTEMPTS_LIMIT}
 * @param initialDelay       Wartezeit vor dem ersten weiteren Versuch, jede weitere verdoppelt sie; 0 bis 60 s
 * @param maxDelay           längste Wartezeit, mindestens {@code initialDelay}, höchstens 300 s
 * @param pauseAfterFailures nach so vielen Inhaltsabrufen nacheinander ohne Verbindung hält sich ein Auftrag an;
 *                           1 bis {@value #PAUSE_AFTER_LIMIT}
 * @param resumeInitialDelay ein so angehaltener Auftrag läuft nach dieser Wartezeit von selbst wieder an
 *                           (Angelo 11.11.2026: WLAN); jeder weitere vergebliche Anlauf verdoppelt sie; 1 s bis 10 min
 * @param resumeMaxDelay     längste Wartezeit bis zum nächsten Anlauf, mindestens {@code resumeInitialDelay},
 *                           höchstens 1 h; es wird nie ganz aufgegeben
 */
public record RetrySettings(int maxAttempts, Duration initialDelay, Duration maxDelay, int pauseAfterFailures,
        Duration resumeInitialDelay, Duration resumeMaxDelay) {

    /** Präfix der Schlüssel unter {@code space-harvester}. */
    public static final String PREFIX = "jobs.retry.";
    public static final String MAX_ATTEMPTS = "max-attempts";
    public static final String INITIAL_DELAY = "initial-delay";
    public static final String MAX_DELAY = "max-delay";
    public static final String PAUSE_AFTER_FAILURES = "pause-after-failures";
    public static final String RESUME_INITIAL_DELAY = "resume-initial-delay";
    public static final String RESUME_MAX_DELAY = "resume-max-delay";
    /** Standard für {@link #resumeInitialDelay()}. */
    public static final Duration DEFAULT_RESUME_INITIAL_DELAY = Duration.ofSeconds(10);
    /** Standard für {@link #resumeMaxDelay()}. */
    public static final Duration DEFAULT_RESUME_MAX_DELAY = Duration.ofMinutes(10);

    /** Grenze des REST-Clients der Quelle. */
    public static final int MAX_ATTEMPTS_LIMIT = 10;
    public static final int PAUSE_AFTER_LIMIT = 1000;
    private static final Duration MAX_INITIAL_DELAY = Duration.ofSeconds(60);
    private static final Duration MAX_MAX_DELAY = Duration.ofSeconds(300);
    private static final Duration MIN_RESUME_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RESUME_INITIAL_DELAY = Duration.ofMinutes(10);
    private static final Duration MAX_RESUME_MAX_DELAY = Duration.ofHours(1);

    /**
     * Zwei weitere Versuche nach etwa 1 und 2 Sekunden (höchstens 30 s), wie bisher im Dienst und im Enterprise AI
     * Client; nach fünf Netzfehlern nacheinander anhalten und nach 10 s, 20 s, 40 s … höchstens 10 min wieder anlaufen.
     */
    public static final RetrySettings DEFAULT = new RetrySettings(2, Duration.ofSeconds(1), Duration.ofSeconds(30), 5);

    /** Mit den Standard-Wartezeiten des Wiederanlaufs ({@link #DEFAULT_RESUME_INITIAL_DELAY}). */
    public RetrySettings(int maxAttempts, Duration initialDelay, Duration maxDelay, int pauseAfterFailures) {
        this(maxAttempts, initialDelay, maxDelay, pauseAfterFailures, DEFAULT_RESUME_INITIAL_DELAY,
                DEFAULT_RESUME_MAX_DELAY);
    }

    /** Dieselben Wiederholungen mit anderen Wartezeiten des Wiederanlaufs. */
    public RetrySettings withResume(Duration initial, Duration max) {
        return new RetrySettings(maxAttempts, initialDelay, maxDelay, pauseAfterFailures, initial, max);
    }

    /**
     * Die Wartezeit vor dem Anlauf Nummer {@code attempt} (ab 0) nach einem Selbst-Anhalten wegen fehlender
     * Verbindung: {@link #resumeInitialDelay()} verdoppelt je Anlauf, höchstens {@link #resumeMaxDelay()}.
     */
    public Duration resumeDelay(int attempt) {
        Duration delay = resumeInitialDelay;
        for (int i = 0; i < attempt && delay.compareTo(resumeMaxDelay) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(resumeMaxDelay) > 0 ? resumeMaxDelay : delay;
    }

    public RetrySettings {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        resumeInitialDelay = resumeInitialDelay == null ? DEFAULT_RESUME_INITIAL_DELAY : resumeInitialDelay;
        resumeMaxDelay = resumeMaxDelay == null ? DEFAULT_RESUME_MAX_DELAY : resumeMaxDelay;
        if (resumeInitialDelay.compareTo(MIN_RESUME_DELAY) < 0
                || resumeInitialDelay.compareTo(MAX_RESUME_INITIAL_DELAY) > 0) {
            throw new IllegalArgumentException(RESUME_INITIAL_DELAY + " muss zwischen 1 Sekunde und 10 Minuten liegen");
        }
        if (resumeMaxDelay.compareTo(resumeInitialDelay) < 0 || resumeMaxDelay.compareTo(MAX_RESUME_MAX_DELAY) > 0) {
            throw new IllegalArgumentException(RESUME_MAX_DELAY + " muss zwischen " + RESUME_INITIAL_DELAY
                    + " und 1 Stunde liegen");
        }
        if (maxAttempts < 0 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException(MAX_ATTEMPTS + " muss zwischen 0 und " + MAX_ATTEMPTS_LIMIT
                    + " liegen");
        }
        if (initialDelay.isNegative() || initialDelay.compareTo(MAX_INITIAL_DELAY) > 0) {
            throw new IllegalArgumentException(INITIAL_DELAY + " muss zwischen 0 und 60 Sekunden liegen");
        }
        if (maxDelay.compareTo(initialDelay) < 0 || maxDelay.compareTo(MAX_MAX_DELAY) > 0) {
            throw new IllegalArgumentException(MAX_DELAY + " muss zwischen " + INITIAL_DELAY
                    + " und 300 Sekunden liegen");
        }
        if (pauseAfterFailures < 1 || pauseAfterFailures > PAUSE_AFTER_LIMIT) {
            throw new IllegalArgumentException(PAUSE_AFTER_FAILURES + " muss zwischen 1 und " + PAUSE_AFTER_LIMIT
                    + " liegen");
        }
    }
}

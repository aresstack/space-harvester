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
 */
public record RetrySettings(int maxAttempts, Duration initialDelay, Duration maxDelay, int pauseAfterFailures) {

    /** Präfix der Schlüssel unter {@code space-harvester}. */
    public static final String PREFIX = "jobs.retry.";
    public static final String MAX_ATTEMPTS = "max-attempts";
    public static final String INITIAL_DELAY = "initial-delay";
    public static final String MAX_DELAY = "max-delay";
    public static final String PAUSE_AFTER_FAILURES = "pause-after-failures";

    /** Grenze des REST-Clients der Quelle. */
    public static final int MAX_ATTEMPTS_LIMIT = 10;
    public static final int PAUSE_AFTER_LIMIT = 1000;
    private static final Duration MAX_INITIAL_DELAY = Duration.ofSeconds(60);
    private static final Duration MAX_MAX_DELAY = Duration.ofSeconds(300);

    /**
     * Zwei weitere Versuche nach etwa 1 und 2 Sekunden (höchstens 30 s), wie bisher im Dienst und im Enterprise AI
     * Client; nach fünf Netzfehlern nacheinander anhalten.
     */
    public static final RetrySettings DEFAULT = new RetrySettings(2, Duration.ofSeconds(1), Duration.ofSeconds(30), 5);

    public RetrySettings {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
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

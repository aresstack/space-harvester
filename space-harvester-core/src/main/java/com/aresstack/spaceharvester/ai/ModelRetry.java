package com.aresstack.spaceharvester.ai;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Wiederholt eine Modellanfrage bei vorübergehenden Fehlern (keine Verbindung, Zeit abgelaufen), mit verdoppelter
 * Wartezeit bis höchstens 30 s. Die eine Stelle für alle Analyseschritte (ADR 0035, P1b): vorher nur in der
 * thematischen Zusammenfassung. Andere Fehler (abgelehnter Schlüssel, unbrauchbare oder abgeschnittene Antwort) gehen
 * sofort weiter, denn dieselbe Anfrage gäbe dasselbe Ergebnis.
 */
public final class ModelRetry {

    private static final System.Logger LOG = System.getLogger(ModelRetry.class.getName());

    /** Wartezeit vor der ersten Wiederholung für {@code StepModel}; in Tests kürzer. */
    public static volatile long defaultBackoffMillis = 2_000;

    private ModelRetry() {
    }

    /** Wird vor jeder Wiederholung benachrichtigt; darf werfen, um abzubrechen (etwa bei einem abgebrochenen Auftrag). */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param nextAttempt Nummer des folgenden Versuchs (2 für die erste Wiederholung)
         * @param cause       der Fehler des vorigen Versuchs; sein Grund gehört in die Diagnose
         */
        void retrying(int nextAttempt, AiServiceException cause);

        /** Unterbrochen während der Wartezeit; Standard: {@link CancellationException}. */
        default RuntimeException interrupted() {
            return new CancellationException("Modellanfrage unterbrochen");
        }

        /** Nur ins Log, mit Grundcode, nie mit Text oder Antwort. */
        Listener LOG_ONLY = (next, cause) -> LOG.log(System.Logger.Level.WARNING,
                "Modellanfrage gescheitert (AI_" + cause.reason() + "), Versuch " + next + " folgt");
    }

    /**
     * Nur ins Log; ist {@code cancelled} gesetzt, endet die Wiederholung mit {@link CancellationException}. Der letzte
     * Fehler samt Grund geht wie bisher an den Aufrufer und in die Diagnose des Auftrags.
     */
    public static Listener logging(BooleanSupplier cancelled) {
        return (next, cause) -> {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Modellanfrage abgebrochen");
            }
            Listener.LOG_ONLY.retrying(next, cause);
        };
    }

    /** Ob ein Fehler vorübergehend ist und eine Wiederholung lohnt. */
    public static boolean transientFailure(AiServiceException e) {
        return e.reason() == AiServiceException.Reason.UNREACHABLE
                || e.reason() == AiServiceException.Reason.TIMEOUT;
    }

    /**
     * Ruft {@code call} bis zu {@code retries + 1} Mal.
     *
     * @param retries       Wiederholungen nach dem ersten Versuch, negative gelten als 0
     * @param backoffMillis Wartezeit vor der ersten Wiederholung, danach verdoppelt
     * @throws AiServiceException der letzte Fehler, wenn er nicht vorübergehend war oder die Versuche aufgebraucht sind
     */
    public static <T> T call(int retries, long backoffMillis, Listener listener, Supplier<T> call) {
        int limit = Math.max(0, retries);
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (AiServiceException e) {
                if (!transientFailure(e) || attempt >= limit) {
                    throw e;
                }
                listener.retrying(attempt + 2, e);
                try {
                    Thread.sleep(Math.min(30_000, Math.max(0, backoffMillis) << Math.min(attempt, 20)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw listener.interrupted();
                }
            }
        }
    }
}

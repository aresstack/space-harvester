package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.network.NetworkState;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Warum sich eine Seite nicht laden ließ, für die Diagnose eines Auftrags (Angelo 26.11.2026: verständlich auf
 * Deutsch, der Grund je Seite in der Diagnose). Eine Stelle für alle Teile, die Abrufe des
 * {@code AcquisitionScheduler} zählen. Der Text nennt die Art in Worten und in Klammern den Code, nie Seiten-IDs oder
 * Adressen.
 */
final class PageFailures {

    private PageFailures() {
    }

    /**
     * @param failure die Ursache des Abrufs oder {@code null}, wenn der Planer die Seite ohne Ausnahme nicht lud: Dann
     *                durfte der Anlass sie nicht laden (Einstellung der Quelle oder Ausschluss)
     */
    static String reason(Throwable failure) {
        if (failure == null) {
            return "Abruf für diesen Anlass nicht erlaubt (Einstellung der Quelle oder ausgeschlossen)";
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof CancellationException) {
                return "Abruf zurückgezogen";
            }
            if (current instanceof SourceAccessException access) {
                return text(access.reason()) + " (" + access.reason().name() + ")";
            }
            if (current instanceof CacheMissException) {
                return "bei der Quelle nicht vorhanden oder nicht freigegeben";
            }
        }
        if (NetworkState.networkFailure(failure)) {
            return "keine Netzverbindung (" + failure.getClass().getSimpleName() + ")";
        }
        if (failure instanceof IllegalStateException && failure.getMessage() != null) {
            // Etwa „Seite nicht abgelegt“ aus PageQueries.acquire: eigene Texte des Beschaffungswegs.
            return failure.getMessage();
        }
        return "unerwarteter Fehler (" + failure.getClass().getSimpleName() + ")";
    }

    private static String text(SourceAccessException.Reason reason) {
        return switch (reason) {
            case UNAVAILABLE -> "Quelle nicht erreichbar oder Verbindung abgerissen, auch nach den Wiederholungen";
            case RATE_LIMITED -> "Quelle drosselt Anfragen, auch nach den Wiederholungen";
            case ACCESS_DENIED -> "Zugriff verweigert";
            case NOT_FOUND -> "bei der Quelle nicht gefunden";
            case INVALID_RESPONSE -> "Antwort der Quelle nicht verwertbar";
            case UNSUPPORTED -> "von der Quelle nicht unterstützt, etwa eine Dateiart ohne Textauszug";
        };
    }
}

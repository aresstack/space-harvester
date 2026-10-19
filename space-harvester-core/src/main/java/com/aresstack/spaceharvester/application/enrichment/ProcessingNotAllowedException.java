package com.aresstack.spaceharvester.application.enrichment;

/**
 * Ein Prozessor darf die Seite nicht (mehr) bearbeiten, etwa weil die Freigabe der Quelle für den KI-Dienst entzogen
 * wurde, nachdem die Berechnung eingereiht war (ADR 0015). Kein Fehler der Seite: {@link Enrichments} speichert dann
 * nichts und nennt die Seite nicht im Log.
 */
public final class ProcessingNotAllowedException extends IllegalStateException {

    public ProcessingNotAllowedException(String message) {
        super(message);
    }
}

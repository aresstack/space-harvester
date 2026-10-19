package com.aresstack.spaceharvester.application.enrichment.statements;

/** Kein Vorschlag für einen Absatz: das Modell scheiterte oder antwortete unlesbar. Trägt nie Text der Seite. */
public final class StatementExtractionException extends RuntimeException {

    public StatementExtractionException(String reason) {
        super(reason);
    }

    public StatementExtractionException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

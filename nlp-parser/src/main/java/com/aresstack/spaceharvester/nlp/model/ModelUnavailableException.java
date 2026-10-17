package com.aresstack.spaceharvester.nlp.model;

/**
 * Ein Modell ist nicht verfügbar oder passt nicht: nicht hinterlegt, Datei fehlt, Prüfsumme, Version oder
 * Eingabeschema weichen ab. Die Meldung sagt, was fehlt und was erwartet war; nichts wird nachgeladen oder trainiert.
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

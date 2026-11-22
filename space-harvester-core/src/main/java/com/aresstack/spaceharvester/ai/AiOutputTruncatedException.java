package com.aresstack.spaceharvester.ai;

/**
 * Der Dienst hat die Antwort am Ausgabelimit beendet ({@code finish_reason = length}): Sie ist unvollständig. Wer
 * die Eingabe teilen kann (etwa die Update-Zusammenfassung), fragt kleinere Teile, statt den Auftrag kippen zu lassen.
 */
public class AiOutputTruncatedException extends AiServiceException {

    public AiOutputTruncatedException(String message) {
        super(Reason.INVALID_RESPONSE, message);
    }
}

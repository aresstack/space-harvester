package com.aresstack.spaceharvester.application.export;

/** Die Exportablage hat ihre Gesamtgrenze in Bytes erreicht; ein Artefakt wird nie abgeschnitten. */
public class ExportStorageFullException extends RuntimeException {

    public ExportStorageFullException(long maxBytes) {
        super("Die Exportablage ist voll (höchstens " + maxBytes + " Bytes); ältere Exporte laufen mit ihrer "
                + "Aufbewahrung ab");
    }
}

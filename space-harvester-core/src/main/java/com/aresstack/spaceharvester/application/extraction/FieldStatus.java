package com.aresstack.spaceharvester.application.extraction;

/** Aussagequalität eines extrahierten Felds. */
public enum FieldStatus {
    /** Steht ausdrücklich im Dokument. */
    EXPLICIT,
    /**
     * Aus Dokumentfakten abgeleitet, nur durch Normalisieren oder Rechnen auf ihnen (etwa ein Datum aus „Ende des
     * Haushaltsjahres 2026“). Nie das Ergebnis einer fachlichen Regel; Regelergebnisse sind keine Extraktion.
     */
    DERIVED,
    /** Nicht gefunden; der Wert ist dann leer. */
    NOT_FOUND,
    /** Mehrere Aussagen zu diesem Feld widersprechen sich; jede bleibt als eigenes Feld stehen. */
    CONFLICT
}

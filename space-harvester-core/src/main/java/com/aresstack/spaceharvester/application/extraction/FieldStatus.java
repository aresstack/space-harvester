package com.aresstack.spaceharvester.application.extraction;

/** Aussagequalität eines extrahierten Felds. */
public enum FieldStatus {
    /** Steht ausdrücklich im Dokument. */
    EXPLICIT,
    /** Aus anderen Angaben abgeleitet. */
    DERIVED,
    /** Nicht gefunden; der Wert ist dann leer. */
    NOT_FOUND,
    /** Mehrere Aussagen zu diesem Feld widersprechen sich; jede bleibt als eigenes Feld stehen. */
    CONFLICT
}

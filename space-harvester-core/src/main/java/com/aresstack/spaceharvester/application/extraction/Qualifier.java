package com.aresstack.spaceharvester.application.extraction;

/**
 * Art einer Aussage, wo das Dokument sie unterscheidet: Eine beantragte und eine genehmigte Fristverlängerung sind
 * verschiedene Felder und werden nie zusammengeführt.
 */
public enum Qualifier {
    /** Keine Unterscheidung. */
    NONE,
    /** Beantragt. */
    REQUESTED,
    /** Genehmigt. */
    GRANTED
}

package com.aresstack.research4j.judge;

/**
 * Where a source lies against the fence. Only {@link #AUSSEN} keeps a source out of the basket: a mixed source stays,
 * and an unclear one stays as an open question, never deleted.
 */
public enum FenceFit {
    /** The whole source lies inside the fence. */
    INNEN,
    /** Parts lie inside and parts outside. */
    GEMISCHT,
    /** The source lies wholly outside. */
    AUSSEN,
    /** Title and beginning do not tell. */
    UNKLAR;

    /** Whether a source with this fit goes into the basket. */
    public boolean belongsInBasket() {
        return this != AUSSEN;
    }
}

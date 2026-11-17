package com.aresstack.spaceharvester.application.changelog.topics;

/**
 * Ein Signal im Netz der Änderungseinheiten (ADR 0030, Nachtrag Abschnitt 4): Ähnlichkeit zweier Bündel in [0,1].
 * Zeit und Bearbeiter sind bewusst kein Signal.
 */
public enum Signal {
    /** Cosinus der Vektoren (nur für freigegebene Seiten eingebettet). */
    VECTOR,
    /** Gleicher Abschnitt, gleiche Seite, gleiche Elternseite oder gemeinsamer Teilbaum. */
    SECTION,
    /** Gemeinsame Begriffe (Jaccard). */
    TERMS,
    /** Gemeinsame Labels (Jaccard), sobald der Unterschied sie trägt. */
    LABELS,
    /** Verweis zwischen den Seiten. */
    REFERENCES,
    /** Gemeinsame Objekte in Aussagen (ADR 0024), experimentell. */
    STATEMENTS
}

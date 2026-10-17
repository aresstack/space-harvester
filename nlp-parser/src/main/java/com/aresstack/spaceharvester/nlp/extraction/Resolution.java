package com.aresstack.spaceharvester.nlp.extraction;

/** Bezug einer Erwähnung. Eine Auflösung (Koreferenz, Entitäten) gehört in eine spätere Schicht. */
public enum Resolution {
    /** Eine Nominalgruppe im Wortlaut; was sie bezeichnet, ist nicht festgestellt, aber auch nicht offen verwiesen. */
    SURFACE,
    /** Pronomen ({@code er}, {@code ihn}, Relativpronomen): der Bezug ist offen und wird nicht geraten. */
    UNRESOLVED
}

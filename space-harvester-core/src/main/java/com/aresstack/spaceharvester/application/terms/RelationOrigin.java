package com.aresstack.spaceharvester.application.terms;

import java.util.Locale;

/** Herkunft einer Beziehung; ändert sich nie, auch nicht durch Bestätigen. */
public enum RelationOrigin {
    /** Von einem Bearbeiter angelegt. */
    MANUAL,
    /** Regelbasiert aus einem belegten Textmuster vorgeschlagen. */
    RULE,
    /** Von einem KI-Modell vorgeschlagen (#122, #336). */
    AI;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException für einen unbekannten Wert */
    public static RelationOrigin of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}

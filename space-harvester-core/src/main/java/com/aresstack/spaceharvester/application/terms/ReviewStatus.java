package com.aresstack.spaceharvester.application.terms;

import java.util.Locale;

/** Prüfstatus einer Beziehung, getrennt von ihrer Herkunft. */
public enum ReviewStatus {
    /** Unbestätigter Vorschlag; ein Vorschlagslauf seiner Herkunft darf ihn ersetzen oder zurückziehen. */
    PROPOSED,
    /** Bestätigt; kein Vorschlagslauf ändert ihn. */
    CONFIRMED,
    /** Abgelehnt: dauerhafte Sperre, derselbe Schlüssel wird nie wieder vorgeschlagen. */
    REJECTED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @throws IllegalArgumentException für einen unbekannten Wert */
    public static ReviewStatus of(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}

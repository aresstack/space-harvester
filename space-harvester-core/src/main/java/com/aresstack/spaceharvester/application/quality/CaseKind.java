package com.aresstack.spaceharvester.application.quality;

import java.util.Locale;
import java.util.Optional;

/** Art einer Testfrage (Spalte {@code art}); eine Frage kann mehrere tragen. */
public enum CaseKind {
    /** Antwort auf einer Seite, Frage mit den Wörtern der Seite (Vorgabe). */
    EINFACH,
    /** Antwort über mehrere Seiten verteilt; zusätzlich zählt der Anteil der Fundstellen unter den ersten 10. */
    VERTEILT,
    /** Mehrdeutige Abkürzung. */
    AKRONYM,
    /** Frage nutzt ein anderes Wort als die Seite. */
    SYNONYM,
    /** Antwort steht nirgends; zählt nur, ob die Suche zu Recht nichts Brauchbares liefert. */
    KEINE;

    /** Schreibweise in der Datei. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<CaseKind> parse(String text) {
        for (CaseKind kind : values()) {
            if (kind.label().equals(text.strip().toLowerCase(Locale.ROOT))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}

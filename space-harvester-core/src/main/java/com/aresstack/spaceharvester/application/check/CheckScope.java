package com.aresstack.spaceharvester.application.check;

import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;

/** Welche Aussagen der Prüfschritt dem Modell zeigt (ADR 0032, FL4). Ungültige Aussagen nie. */
public enum CheckScope {
    /**
     * Standard: Aussagen unter PLAUSIBLE und alle Aussagen der Vorstufe. Prüft keine sicheren Aussagen des Originals,
     * findet dort also auch kein „falsch trotz PLAUSIBLE“ (der Prüfstand weist beide Auswahlen aus).
     */
    UNCERTAIN_AND_STAGE("uncertain-and-stage"),
    /** Alle Aussagen außer den ungültigen. */
    ALL("all");

    private final String value;

    CheckScope(String value) {
        this.value = value;
    }

    /** Der Wert in der Konfiguration. */
    public String value() {
        return value;
    }

    /**
     * Liest den Wert; fehlend oder leer heißt {@link #UNCERTAIN_AND_STAGE}.
     *
     * @throws IllegalArgumentException bei einem unbekannten Wert
     */
    public static CheckScope parse(String value) {
        if (value == null || value.isBlank()) {
            return UNCERTAIN_AND_STAGE;
        }
        String text = value.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        for (CheckScope scope : values()) {
            if (scope.value.equals(text)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Erwartet uncertain-and-stage oder all");
    }

    /**
     * Ob eine Aussage zu dieser Auswahl gehört (nach der Stufe des Parsers). Aussagen der Programmanalyse nie: Ihr
     * Beleg ist eine Quellstelle, kein Satz, den das Modell mit der Aussage vergleichen könnte (ADR 0041, Punkt 7).
     */
    public boolean includes(ParsedStatements.Statement statement) {
        ParsedStatements.Origin origin = statement.derivation().origin();
        if (statement.quality() == ParsedStatements.Quality.INVALID || origin == ParsedStatements.Origin.MODELL
                || origin == ParsedStatements.Origin.PROGRAMM) {
            return false;
        }
        return this == ALL || statement.quality() != ParsedStatements.Quality.PLAUSIBLE
                || origin == ParsedStatements.Origin.VORSTUFE;
    }
}

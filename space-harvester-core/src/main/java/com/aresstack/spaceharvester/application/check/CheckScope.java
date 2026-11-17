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

    /** Ob eine Aussage zu dieser Auswahl gehört (nach der Stufe des Parsers). */
    public boolean includes(ParsedStatements.Statement statement) {
        ParsedStatements.Origin origin = statement.derivation().origin();
        if (statement.quality() == ParsedStatements.Quality.INVALID || origin == ParsedStatements.Origin.MODELL) {
            return false;
        }
        return this == ALL || statement.quality() != ParsedStatements.Quality.PLAUSIBLE
                || origin == ParsedStatements.Origin.VORSTUFE;
    }
}

package com.aresstack.spaceharvester.application.terms;

import java.util.Locale;

/**
 * Art einer Begriffsbeziehung (#120). Keine Art wird transitiv geschlossen: Aus {@code A synonym B} und
 * {@code B synonym C} folgt nichts über {@code A} und {@code C}.
 */
public enum RelationType {
    /** {@code from} ist Abkürzung von {@code to} ({@code RSL} → {@code Rückmeldeschlüssel}); gerichtet. */
    ABBREVIATION(true),
    /** Im Geltungsbereich gleichwertige Benennung; ungerichtet. */
    SYNONYM(false),
    /** Schreib- oder Formvariante ({@code E-Mail} – {@code Email}, {@code Beleg} – {@code Belege}); ungerichtet. */
    VARIANT(false),
    /** Fachlich verwandt, keine Gleichsetzung; ungerichtet und nie zur Suchausweitung. */
    RELATED(false);

    private final boolean directed;

    RelationType(boolean directed) {
        this.directed = directed;
    }

    /** Ob die Reihenfolge {@code from} → {@code to} Bedeutung trägt. */
    public boolean directed() {
        return directed;
    }

    /** Ob eine bestätigte Beziehung dieser Art eine Suche ausweiten darf ({@link TermRelations#expansions}). */
    public boolean expandsSearch() {
        return this != RELATED;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @throws IllegalArgumentException für einen unbekannten Wert
     */
    public static RelationType of(String id) {
        for (RelationType type : values()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unbekannte Beziehungsart: " + id);
    }
}

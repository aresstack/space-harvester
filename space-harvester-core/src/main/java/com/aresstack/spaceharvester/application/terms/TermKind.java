package com.aresstack.spaceharvester.application.terms;

import java.util.Locale;

/** Art eines Begriffs; {@link #id()} ist der Wert in Ergebnissen und in der REST-API. */
public enum TermKind {
    /** Nomen oder Nominalgruppe ({@code Sachbearbeitung}, {@code Confluence Data Center}). */
    NOUN,
    /** Akronym aus Großbuchstaben und Ziffern ({@code RSL}, {@code API}); ein Plural-{@code s} gehört dazu. */
    ACRONYM,
    /**
     * Kennung: Buchstaben mit Ziffern, Unterstrich oder innerem Punkt, oder Binnenmajuskel ({@code ISO27001},
     * {@code order_id}).
     */
    IDENTIFIER;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @throws IllegalArgumentException für einen unbekannten Wert
     */
    public static TermKind of(String id) {
        for (TermKind kind : values()) {
            if (kind.id().equals(id)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unbekannte Begriffsart: " + id);
    }
}

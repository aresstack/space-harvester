package com.aresstack.spaceharvester.nlp.extraction;

import java.util.Objects;

/**
 * Ein Beteiligter einer Aussage: die Erwähnung, ihre semantische Rolle, die grammatische Relation, aus der sie
 * stammt, und wie sicher die Zuordnung ist.
 *
 * @param role     semantische Rolle
 * @param mention  ID der Erwähnung
 * @param relation UD-Relation zum Prädikat ({@code nsubj}, {@code obj}, {@code obl:arg}) oder die vom Rückfall
 *                 vorgeschlagene Funktion ({@code nsubj?}, {@code obj?})
 * @param marker   Präposition im Wortlaut ({@code am}, {@code von}) oder {@code null}
 * @param priority Rang in der priorisierten Sicht
 * @param basis    Grundlage der Zuordnung
 * @param certain  {@code false}, wenn die Zuordnung abgeleitet, widersprüchlich oder unbestätigt ist
 */
public record Argument(Role role, String mention, String relation, String marker, Priority priority, Basis basis,
                       boolean certain) {

    public Argument {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(mention, "mention");
        Objects.requireNonNull(relation, "relation");
    }

    public Argument uncertain() {
        return new Argument(role, mention, relation, marker, priority, basis, false);
    }
}

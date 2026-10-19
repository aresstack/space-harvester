package com.aresstack.spaceharvester.application.facts;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lesender Port der Fakten (wie {@code FactStorePort} im Enterprise AI Client, nur die Abfragen). Geschrieben wird nie
 * direkt: Die Ablage der Anreicherungen leitet die Fakten beim Speichern eines Ergebnisses ab
 * ({@link EnrichmentFacts}) und ersetzt und löscht sie mit ihm. Rechte prüft der Port nicht; das tut
 * {@link PageFacts}.
 */
public interface FactStore {

    /** Höchstens so viele Fakten liefert {@link #facts} je Aufruf. */
    int MAX_LIMIT = 1_000;

    /** Alle Fakten einer Seite (wie {@code factsOf(resource)}), nach Prozessor und Position. */
    List<MetadataFact> factsOf(PageKey page);

    /**
     * Fakten über alle Seiten der genannten Quellen (wie {@code factsOfType} und {@code factsWithArguments}), aufsteigend
     * nach {@link MetadataFact#id()}.
     *
     * @param after nur Fakten mit größerer Kennung (Fortsetzung) oder {@code null}
     * @param limit 1 bis {@value #MAX_LIMIT}
     */
    List<MetadataFact> facts(Query query, String after, int limit);

    /**
     * Eine Abfrage.
     *
     * @param type       nur dieser Typ oder {@code null} für alle
     * @param conditions alle müssen gelten; leer für keine
     * @param sourceIds  nur Seiten dieser Quellen; leer heißt keine
     */
    record Query(String type, List<ArgumentCondition> conditions, Set<String> sourceIds) {

        public Query {
            if (type != null) {
                MetadataFact.requireName("type", type);
            }
            conditions = List.copyOf(conditions);
            sourceIds = Set.copyOf(sourceIds);
            if (type == null && conditions.isEmpty()) {
                throw new IllegalArgumentException("Abfrage braucht einen Typ oder eine Bedingung");
            }
        }

        /** Ob ein Fakt die Abfrage erfüllt; für Ablagen ohne eigenen Index. */
        public boolean matches(MetadataFact fact) {
            Objects.requireNonNull(fact, "fact");
            if (!sourceIds.contains(fact.page().sourceId()) || type != null && !type.equals(fact.type())) {
                return false;
            }
            for (ArgumentCondition condition : conditions) {
                if (!fact.values(condition.role()).contains(condition.value())) {
                    return false;
                }
            }
            return true;
        }
    }
}

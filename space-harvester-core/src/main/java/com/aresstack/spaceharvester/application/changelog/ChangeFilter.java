package com.aresstack.spaceharvester.application.changelog;

import java.time.Instant;
import java.util.Set;

/**
 * Suche in den rohen Zeilen des Protokolls (ADR 0030, Abschnitt 4), für den Dialog „Änderungsprotokoll“.
 *
 * @param sourceId  nur diese Quelle, {@code null} für alle
 * @param text      Wörter, die alle im alten oder neuen Titel vorkommen (als Wortanfang), oder genau die Seiten-ID;
 *                  {@code null} oder leer für alle
 * @param kinds     nur diese Arten, leer für alle
 * @param triggers  nur diese Auslöser, leer für alle
 * @param spaceKey  nur dieser Space, {@code null} für alle
 * @param from      ab diesem Zeitpunkt (einschließlich), {@code null} ohne Grenze
 * @param to        bis vor diesen Zeitpunkt (ausschließlich), {@code null} ohne Grenze
 * @param offset    so viele Treffer überspringen
 * @param limit     höchstens so viele Treffer, 1 bis {@link #MAX_LIMIT}
 */
public record ChangeFilter(String sourceId, String text, Set<ChangeKind> kinds, Set<ChangeTrigger> triggers,
                           String spaceKey, Instant from, Instant to, int offset, int limit) {

    public static final int MAX_LIMIT = 1000;

    public ChangeFilter {
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        triggers = triggers == null ? Set.of() : Set.copyOf(triggers);
        text = text == null || text.isBlank() ? null : text.strip();
        if (offset < 0) {
            throw new IllegalArgumentException("offset < 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit außerhalb 1.." + MAX_LIMIT);
        }
    }

    /** Alle Zeilen einer Quelle (oder aller Quellen), die ersten {@code limit}. */
    public static ChangeFilter all(String sourceId, int limit) {
        return new ChangeFilter(sourceId, null, Set.of(), Set.of(), null, null, null, 0, limit);
    }

    /** Die Abfrage an die Ablage für die Quellen, die der Aufrufer sehen darf; neueste zuerst. */
    ChangeQuery query(java.util.Collection<String> sourceIds) {
        return new ChangeQuery(Set.copyOf(sourceIds), kinds, triggers, spaceKey, text, from, true, to, false, true);
    }
}

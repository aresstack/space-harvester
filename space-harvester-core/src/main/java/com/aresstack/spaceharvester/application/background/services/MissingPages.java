package com.aresstack.spaceharvester.application.background.services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Seiten ohne aktuelles Ergebnis eines Dienstes, je Quelle und Space, mit den Rechten des Aufrufers.
 *
 * @param pages        Seiten-IDs je Bereich; leere Bereiche fallen weg
 * @param inaccessible Seiten der Menge, die der Aufrufer nicht sehen darf; nur als Zahl, nie mit Titeln
 */
public record MissingPages(Map<Scope, Set<String>> pages, int inaccessible) {

    /** Nichts fehlt. */
    public static final MissingPages NONE = new MissingPages(Map.of(), 0);

    public MissingPages {
        Map<Scope, Set<String>> copy = new LinkedHashMap<>();
        pages.forEach((scope, ids) -> {
            Objects.requireNonNull(scope, "scope");
            if (!ids.isEmpty()) {
                copy.put(scope, Set.copyOf(new TreeSet<>(ids)));
            }
        });
        pages = java.util.Collections.unmodifiableMap(copy);
        if (inaccessible < 0) {
            throw new IllegalArgumentException("inaccessible < 0");
        }
    }

    /** Ob keine Seite fehlt. */
    public boolean empty() {
        return pages.isEmpty();
    }

    /**
     * Ein Bereich: Quelle und Space; je Bereich ein Unterauftrag.
     *
     * @param spaceKey Space oder {@code null}, wenn die Quelle keine Spaces kennt
     */
    public record Scope(String sourceId, String spaceKey) {

        public Scope {
            Objects.requireNonNull(sourceId, "sourceId");
        }
    }
}

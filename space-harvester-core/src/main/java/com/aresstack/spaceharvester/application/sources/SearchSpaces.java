package com.aresstack.spaceharvester.application.sources;

import java.util.Set;
import java.util.TreeSet;

/**
 * Space-Filter einer Suche in der Quelle (#179): nur diese Spaces ({@code only}, leer für alle) und ohne diese
 * ({@code excluded}, wie die abgehakten Spaces im Enterprise AI Client). Die Quelle setzt den Filter möglichst in
 * ihre eigene Anfrage, damit Treffer anderer Spaces die gesuchten nicht verdrängen; der Aufrufer prüft trotzdem jeden
 * Treffer an seinen Kopfdaten.
 */
public record SearchSpaces(Set<String> only, Set<String> excluded) {

    private static final SearchSpaces ALL = new SearchSpaces(Set.of(), Set.of());

    public SearchSpaces {
        only = Set.copyOf(new TreeSet<>(only == null ? Set.of() : only));
        excluded = Set.copyOf(new TreeSet<>(excluded == null ? Set.of() : excluded));
    }

    /** Ohne Filter. */
    public static SearchSpaces all() {
        return ALL;
    }

    /** Ob eine Seite dieses Space zum Filter passt. */
    public boolean permits(String spaceKey) {
        return (only.isEmpty() || only.contains(spaceKey)) && !excluded.contains(spaceKey);
    }

    /** Ob der Filter etwas einschränkt. */
    public boolean filtered() {
        return !only.isEmpty() || !excluded.isEmpty();
    }
}

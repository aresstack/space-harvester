package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.util.Set;

/**
 * Was von einer Quelle vollständig und aktuell lokal vorliegt (Angelo 27.11.2026): Gesucht wird im lokalen Index; die
 * Online-Suche einer Quelle ist nur der Rückfall für Bereiche, die nicht lokal vorliegen oder nicht aktuell sind, und
 * dient dort nur dem Einsammeln von Seiten. Die Schlagwortsuche fragt die Quelle deshalb nicht für abgedeckte Spaces
 * und gar nicht, wenn der ganze Umfang abgedeckt ist.
 */
public interface LocalCoverage {

    /** Nichts gilt als abgedeckt: Jede Quelle mit Online-Suche wird gefragt (wie bisher). */
    LocalCoverage NONE = (source, scope) -> Coverage.NONE;

    /**
     * Die Abdeckung der Quelle im Umfang {@code scope}; darf die Spaces der Quelle auflisten, scheitert aber nie (im
     * Zweifel nicht abgedeckt).
     */
    Coverage coverage(HarvestSource source, SearchSpaces scope);

    /**
     * @param all    ob der ganze Umfang abgedeckt ist; dann wird die Quelle nicht online gefragt
     * @param spaces die abgedeckten Spaces; die Online-Suche schließt sie aus
     */
    record Coverage(boolean all, Set<String> spaces) {

        public static final Coverage NONE = new Coverage(false, Set.of());

        public Coverage {
            spaces = Set.copyOf(spaces);
        }
    }
}

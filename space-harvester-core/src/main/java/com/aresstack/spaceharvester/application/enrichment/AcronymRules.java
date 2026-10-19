package com.aresstack.spaceharvester.application.enrichment;

import java.util.List;

/**
 * Regeln für Akronyme und technische Kennungen eines Textes; umgesetzt von
 * {@link com.aresstack.spaceharvester.source.EacAcronymRules} mit dem {@code AcronymExtractor} des Enterprise AI
 * Client. Die Kopie bleibt hinter {@code source}, {@link AcronymProcessor} arbeitet mit diesen Typen.
 */
public interface AcronymRules {

    /** Alle Akronyme und Kennungen des Textes, je Suchform ein Fund. */
    List<Finding> analyze(String text);

    /**
     * Alle Vorkommen eines Akronyms oder einer Kennung.
     *
     * @param shortForm   häufigste Schreibweise
     * @param normalized  Suchform: groß, ohne Punkte und Plural-{@code s}
     * @param acronym     {@code true} für ein Akronym, {@code false} für eine technische Kennung
     * @param longForms   unterschiedliche Ausschreibungen in Textreihenfolge, bei Kennungen leer
     * @param occurrences Fundstellen in Textreihenfolge, mindestens eine
     */
    record Finding(String shortForm, String normalized, boolean acronym, List<String> longForms,
                   List<Occurrence> occurrences) {

        public Finding {
            longForms = List.copyOf(longForms);
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * Eine Fundstelle.
     *
     * @param start erste Zeichenposition (UTF-16)
     * @param end   Position nach dem Wort, einschließlich eines Plural-{@code s}
     * @param term  Schreibweise an dieser Stelle, bei Akronymen ohne Plural-{@code s}
     */
    record Occurrence(int start, int end, String term) {
    }
}

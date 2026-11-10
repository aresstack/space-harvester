package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erweiterung der Schlagwortsuche um bestätigte Begriffsbeziehungen (#336, Teil 4): Zu jedem Begriff der Anfrage
 * kommen seine bestätigten Synonyme, Schreibvarianten und Ausschreibungen als eigene Anfragen hinzu, je Quelle im
 * Geltungsbereich der Beziehung (global, Quelle, Space). Vorschläge, Abgelehntes und „verwandt“ wirken nie, und
 * eine Erweiterung wird nicht noch einmal erweitert (keine Transitivität). Rein und zustandslos; was gilt, sagt
 * {@link Relations}, im Core {@code TermRelations.expansions}.
 */
public final class SearchExpansion {

    /** Höchstens so viele zusätzliche Anfragen je Quelle, damit die Quellen nicht mit Anfragen überhäuft werden. */
    public static final int MAX_EXPANSIONS = 3;

    /** Die bestätigten Gegenstücke eines Begriffs im Umfang einer Quelle bzw. eines Space. */
    @FunctionalInterface
    public interface Relations {

        /**
         * @param sourceId Quelle der Suche oder {@code null} für eine Suche über alle Quellen (nur globale)
         * @param spaceKey Space der Suche oder {@code null} für die ganze Quelle
         * @return die Gegenstücke in Schreibweise der Beziehung, ohne den Begriff selbst
         */
        List<String> expansions(AccessContext caller, String term, String sourceId, String spaceKey);
    }

    /** Keine Erweiterung. */
    public static final SearchExpansion NONE = new SearchExpansion((caller, term, sourceId, spaceKey) -> List.of());

    private final Relations relations;

    public SearchExpansion(Relations relations) {
        this.relations = Objects.requireNonNull(relations, "relations");
    }

    /**
     * Die zusätzlichen Anfragen zu {@code text} für eine Quelle: zuerst die Gegenstücke der ganzen Anfrage, dann je
     * Wort die Anfrage mit dem Wort ersetzt (nur bei einem knappen Suchausdruck, {@link SearchTerms#isConcise}; ein Satz
     * wird nur als Ganzes nachgeschlagen). Ohne Dopplungen (Groß- und Kleinschreibung zählt nicht), nie die Anfrage
     * selbst, höchstens {@link #MAX_EXPANSIONS}.
     */
    public List<String> expand(AccessContext caller, String text, String sourceId, String spaceKey) {
        String query = text == null ? "" : text.strip();
        if (query.isEmpty() || this == NONE) {
            return List.of();
        }
        Map<String, String> queries = new LinkedHashMap<>();
        String own = query.toLowerCase(Locale.ROOT);
        for (String other : lookup(caller, query, sourceId, spaceKey)) {
            put(queries, other, own);
        }
        List<String> words = SearchTerms.terms(query);
        if (words.size() > 1 && SearchTerms.isConcise(query)) {
            for (String word : words) {
                if (queries.size() >= MAX_EXPANSIONS) {
                    break;
                }
                for (String other : lookup(caller, word, sourceId, spaceKey)) {
                    put(queries, replace(query, word, other), own);
                }
            }
        }
        List<String> result = new ArrayList<>(queries.values());
        return result.size() > MAX_EXPANSIONS ? List.copyOf(result.subList(0, MAX_EXPANSIONS)) : List.copyOf(result);
    }

    private List<String> lookup(AccessContext caller, String term, String sourceId, String spaceKey) {
        try {
            List<String> found = relations.expansions(caller, term, sourceId, spaceKey);
            return found == null ? List.of() : found;
        } catch (RuntimeException e) {
            // Die Suche läuft ohne Erweiterung weiter; nur der Typ ins Log, nie der Begriff.
            return List.of();
        }
    }

    private static void put(Map<String, String> queries, String candidate, String own) {
        String trimmed = candidate == null ? "" : candidate.strip();
        if (!trimmed.isEmpty()) {
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (!key.equals(own)) {
                queries.putIfAbsent(key, trimmed);
            }
        }
    }

    /** Das Wort in der Anfrage durch sein Gegenstück ersetzt, als ganzes Wort, Groß- und Kleinschreibung egal. */
    static String replace(String query, String word, String other) {
        // Wortgrenzen wie SearchTerms: Bindestrich und Unterstrich gehören zum Wort („RSL-Wert“ bleibt).
        Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}_-])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matcher = pattern.matcher(query);
        return matcher.find() ? matcher.replaceFirst(Matcher.quoteReplacement(other)) : query;
    }
}

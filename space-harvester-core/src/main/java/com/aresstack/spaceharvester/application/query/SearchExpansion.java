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

    /**
     * Wie {@link Relations}, mit der Art jeder Beziehung (R1: Ursache am Treffer).
     */
    @FunctionalInterface
    public interface Counterparts {

        /** @return die Gegenstücke mit ihrer Beziehungsart, ohne den Begriff selbst */
        List<Counterpart> counterparts(AccessContext caller, String term, String sourceId, String spaceKey);
    }

    /**
     * Ein Gegenstück eines Begriffs.
     *
     * @param text Schreibweise der Beziehung
     * @param type Art der Beziehung ({@code abbreviation}, {@code synonym}, {@code variant}) oder {@code null}
     */
    public record Counterpart(String text, String type) {
    }

    /**
     * Eine zusätzliche Anfrage und die bestätigte Beziehung, aus der sie entstand.
     *
     * @param query    die Anfrage, wie sie an Index und Quelle geht
     * @param relation Begriff, Gegenstück und Art
     */
    public record Expansion(String query, HitReason.Relation relation) {

        public Expansion {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(relation, "relation");
        }
    }

    /** Keine Erweiterung. */
    public static final SearchExpansion NONE = new SearchExpansion((caller, term, sourceId, spaceKey) -> List.of());

    private final Counterparts relations;

    public SearchExpansion(Relations relations) {
        Objects.requireNonNull(relations, "relations");
        this.relations = (caller, term, sourceId, spaceKey) -> {
            List<String> found = relations.expansions(caller, term, sourceId, spaceKey);
            return found == null ? List.of() : found.stream().map(text -> new Counterpart(text, null)).toList();
        };
    }

    private SearchExpansion(Counterparts relations, boolean typed) {
        this.relations = Objects.requireNonNull(relations, "relations");
    }

    /** Erweiterung, die die Art jeder Beziehung kennt. */
    public static SearchExpansion typed(Counterparts relations) {
        return new SearchExpansion(relations, true);
    }

    /**
     * Die zusätzlichen Anfragen zu {@code text} für eine Quelle: zuerst die Gegenstücke der ganzen Anfrage, dann je
     * Wort die Anfrage mit dem Wort ersetzt (nur bei einem knappen Suchausdruck, {@link SearchTerms#isConcise}; ein Satz
     * wird nur als Ganzes nachgeschlagen). Ohne Dopplungen (Groß- und Kleinschreibung zählt nicht), nie die Anfrage
     * selbst, höchstens {@link #MAX_EXPANSIONS}.
     */
    public List<String> expand(AccessContext caller, String text, String sourceId, String spaceKey) {
        return expansions(caller, text, sourceId, spaceKey).stream().map(Expansion::query).toList();
    }

    /** Wie {@link #expand}, je Anfrage mit der Beziehung, aus der sie entstand. */
    public List<Expansion> expansions(AccessContext caller, String text, String sourceId, String spaceKey) {
        return expansions(caller, text, sourceId, spaceKey, MAX_EXPANSIONS);
    }

    /**
     * Wie {@link #expansions(AccessContext, String, String, String)}, aber ohne die Grenze {@link #MAX_EXPANSIONS}:
     * Wer mehrere Erweiterungen zusammenführt, kappt erst nach dem Entfernen der Dubletten (K5a).
     */
    public List<Expansion> allExpansions(AccessContext caller, String text, String sourceId, String spaceKey) {
        return expansions(caller, text, sourceId, spaceKey, Integer.MAX_VALUE);
    }

    private List<Expansion> expansions(AccessContext caller, String text, String sourceId, String spaceKey, int max) {
        String query = text == null ? "" : text.strip();
        if (query.isEmpty() || this == NONE) {
            return List.of();
        }
        Map<String, Expansion> queries = new LinkedHashMap<>();
        String own = query.toLowerCase(Locale.ROOT);
        for (Counterpart other : lookup(caller, query, sourceId, spaceKey)) {
            put(queries, other.text(), own, new HitReason.Relation(query, other.text(), other.type()));
        }
        List<String> words = SearchTerms.terms(query);
        if (words.size() > 1 && SearchTerms.isConcise(query)) {
            for (String word : words) {
                if (queries.size() >= max) {
                    break;
                }
                for (Counterpart other : lookup(caller, word, sourceId, spaceKey)) {
                    put(queries, replace(query, word, other.text()), own,
                            new HitReason.Relation(word, other.text(), other.type()));
                }
            }
        }
        List<Expansion> result = new ArrayList<>(queries.values());
        return result.size() > max ? List.copyOf(result.subList(0, max)) : List.copyOf(result);
    }

    private List<Counterpart> lookup(AccessContext caller, String term, String sourceId, String spaceKey) {
        try {
            List<Counterpart> found = relations.counterparts(caller, term, sourceId, spaceKey);
            return found == null ? List.of() : found.stream()
                    .filter(other -> other != null && other.text() != null).toList();
        } catch (RuntimeException e) {
            // Die Suche läuft ohne Erweiterung weiter; nur der Typ ins Log, nie der Begriff.
            return List.of();
        }
    }

    private static void put(Map<String, Expansion> queries, String candidate, String own,
                            HitReason.Relation relation) {
        String trimmed = candidate == null ? "" : candidate.strip();
        if (!trimmed.isEmpty()) {
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (!key.equals(own)) {
                queries.putIfAbsent(key, new Expansion(trimmed, relation));
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

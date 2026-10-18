package com.aresstack.spaceharvester.application.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Suchbegriffe aus einer Frage für die Suche der Quellen (wie {@code ResearchQueries} im Enterprise AI Client, dort
 * {@code keywordQueries}, {@code literalQuery} und {@code isConcise}): Eine Quellensuche wie die Confluence-Websuche
 * findet zu einem ganzen Satz wenig, zu seinen Schlüsselwörtern viel. Rein und zustandslos, ohne Modell.
 */
public final class SearchTerms {

    /** Höchstens so viele Wörter hat ein Suchausdruck; längere sind ein Satz, keine Anfrage. */
    public static final int MAX_QUERY_WORDS = 6;
    /** Starke wörtliche Begriffe der {@link #literalQuery}. */
    static final int LITERAL_TERMS = 3;

    private static final Set<String> STOP_WORDS = Set.of(
            "aber", "alle", "als", "auch", "auf", "aus", "bei", "bitte", "das", "dass", "dem", "den", "der", "des",
            "die", "dir", "doch", "du", "ein", "eine", "einen", "einer", "etwas", "finde", "finden", "für", "gibt",
            "hat", "ich", "ist", "kannst", "mal", "mehr", "mir", "mit", "nach", "noch", "oder", "sich", "sind",
            "über", "und", "uns", "von", "was", "welche", "welcher", "wer", "wie", "wir", "wo", "zu", "zum", "zur",
            "confluence", "wiki", "infos", "informationen", "information", "suche", "such", "genau", "the", "and",
            "what", "about", "find");

    /** Wörter einer Bitte, die nie ein wörtlicher Suchbegriff sind (zusätzlich zu {@link #STOP_WORDS}). */
    private static final Set<String> REQUEST_WORDS = Set.of(
            "am", "an", "im", "in", "es", "er", "sie", "wird", "werden", "wurde", "wurden", "beschrieben",
            "beschreibt", "heraus", "bedeutet", "bedeutung", "haben", "kann", "soll", "unternehmenswissen",
            "internen", "interne", "internes", "unternehmen", "welches", "welchen", "warum", "wann", "erkläre",
            "erklären", "recherchiere", "zeige", "nenne", "gib", "is", "are", "how", "which", "does", "of",
            "for", "with", "a");

    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}_-]+");

    private SearchTerms() {
    }

    /** Ob {@code query} ein Suchausdruck und kein Satz ist (höchstens {@value #MAX_QUERY_WORDS} Wörter). */
    public static boolean isConcise(String query) {
        String trimmed = query == null ? "" : query.trim();
        return !trimmed.isEmpty() && trimmed.split("\\s+").length <= MAX_QUERY_WORDS;
    }

    /**
     * Die ersten Schlüsselwörter der Frage einzeln und die ersten beiden zusammen, höchstens {@code maxQueries};
     * ohne Schlüsselwort die gekürzte Frage selbst.
     */
    public static List<String> keywordQueries(String message, int maxQueries) {
        List<String> words = new ArrayList<>();
        for (String word : WORD_SPLIT.split(message)) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (word.length() >= 2 && !STOP_WORDS.contains(lower) && !words.contains(word)) {
                words.add(word);
            }
        }
        List<String> queries = new ArrayList<>();
        for (String word : words) {
            if (queries.size() >= maxQueries - 1) {
                break;
            }
            queries.add(word);
        }
        if (words.size() > 1 && queries.size() < maxQueries) {
            queries.add(words.get(0) + " " + words.get(1));
        }
        if (queries.isEmpty() && !message.trim().isEmpty()) {
            queries.add(shorten(message.trim()));
        }
        return queries;
    }

    /**
     * Die stärksten wörtlichen Begriffe der Frage als eine kurze Anfrage: zuerst kennungsartige Wörter (Akronyme,
     * gemischte Schreibung wie {@code HaVdB}, Ziffern, Bindestriche wie {@code BS-PIMA}), dann großgeschriebene Namen,
     * in der Reihenfolge der Frage; Frage- und Füllwörter zählen nie.
     */
    public static String literalQuery(String request) {
        String text = request == null ? "" : request.trim();
        List<String> words = new ArrayList<>();
        for (String word : WORD_SPLIT.split(text)) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (word.length() >= 2 && !STOP_WORDS.contains(lower) && !REQUEST_WORDS.contains(lower)
                    && !words.contains(word)) {
                words.add(word);
            }
        }
        List<String> ranked = new ArrayList<>(words);
        ranked.sort(Comparator.comparingInt((String word) -> -strength(word)).thenComparingInt(words::indexOf));
        List<String> chosen = ranked.subList(0, Math.min(LITERAL_TERMS, ranked.size()));
        StringBuilder query = new StringBuilder();
        for (String word : words) {
            if (chosen.contains(word)) {
                query.append(query.isEmpty() ? "" : " ").append(word);
            }
        }
        return query.isEmpty() ? shorten(text) : query.toString();
    }

    /**
     * Die Frage ohne Wörter einer Bitte (etwa „wird“, „in“), als Eingabe für {@link #keywordQueries}: als eigene Anfrage
     * an eine Quelle fänden sie nur Rauschen. Nicht im Enterprise AI Client, dort ist {@link #keywordQueries} nur ein
     * Ersatzplan ohne Modell.
     */
    static String withoutRequestWords(String text) {
        List<String> kept = new ArrayList<>();
        for (String word : WORD_SPLIT.split(text == null ? "" : text)) {
            if (!word.isEmpty() && !REQUEST_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                kept.add(word);
            }
        }
        return String.join(" ", kept);
    }

    /**
     * Die Suchbegriffe der Frage in ihrer Reihenfolge, ohne Füll-, Frage- und Bittwörter und ohne Dubletten (ohne
     * Rücksicht auf Groß- und Kleinschreibung); Grundlage der Varianten der progressiven Suche (ADR 0017).
     */
    public static List<String> terms(String text) {
        List<String> words = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String word : WORD_SPLIT.split(text == null ? "" : text)) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (word.length() >= 2 && !STOP_WORDS.contains(lower) && !REQUEST_WORDS.contains(lower)
                    && seen.add(lower)) {
                words.add(word);
            }
        }
        return List.copyOf(words);
    }

    /** 2: kennungsartig, 1: großgeschriebener Name, 0: gewöhnliches Wort. */
    public static int strength(String word) {
        boolean digit = false;
        int upper = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            digit |= Character.isDigit(c);
            if (Character.isUpperCase(c)) {
                upper++;
            }
        }
        if (digit || word.indexOf('-') > 0 || upper >= 2) {
            return 2;
        }
        return upper == 1 && Character.isUpperCase(word.charAt(0)) ? 1 : 0;
    }

    private static String shorten(String text) {
        String[] words = text.split("\\s+");
        if (words.length <= MAX_QUERY_WORDS) {
            return text;
        }
        return String.join(" ", List.of(words).subList(0, MAX_QUERY_WORDS));
    }
}

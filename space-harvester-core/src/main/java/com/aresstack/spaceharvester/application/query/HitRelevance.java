package com.aresstack.spaceharvester.application.query;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Ein Punktwert je Treffer, der über Quellen hinweg vergleichbar ist (Angelo, 23.11.2026): Die Rangfolge einer Suche
 * kommt aus den Quellen bzw. dem Cache und ist nur innerhalb einer Suche sinnvoll. Werden die Treffer zweier Suchen
 * (etwa einer nachgesuchten Quelle) zu einer Liste zusammengeführt, ordnet dieser Wert sie einheitlich: gleiche
 * Rechnung für jede Quelle, nur aus Suchtext, Titel und Auszug, ohne Netz und ohne Modell.
 *
 * <p>Je Suchwort zählt ein Vorkommen im Titel {@value #TITLE} Punkte, im Auszug {@value #SNIPPET}; steht der ganze
 * Suchtext im Titel, kommen {@value #PHRASE_IN_TITLE} dazu, ist der Titel genau der Suchtext {@value #EXACT_TITLE}.
 * Groß-/Kleinschreibung, Akzente und Satzzeichen zählen nicht. Der Wert ist nicht normiert, nur vergleichbar.
 */
public final class HitRelevance {

    static final double TITLE = 3;
    static final double SNIPPET = 1;
    static final double PHRASE_IN_TITLE = 4;
    static final double EXACT_TITLE = 6;

    private HitRelevance() {
    }

    /** Der Punktwert eines Treffers für den Suchtext; 0 ohne Suchwort. */
    public static double of(String query, KeywordSearch.Hit hit) {
        return of(query, hit.title(), hit.snippet());
    }

    /** Wie {@link #of(String, KeywordSearch.Hit)} für Titel und Auszug. */
    public static double of(String query, String title, String snippet) {
        String q = normal(query);
        if (q.isEmpty()) {
            return 0;
        }
        String t = normal(title);
        String s = normal(snippet);
        List<String> words = Arrays.stream(q.split(" ")).filter(word -> !word.isEmpty()).distinct().toList();
        double score = 0;
        for (String word : words) {
            if (contains(t, word)) {
                score += TITLE;
            }
            if (contains(s, word)) {
                score += SNIPPET;
            }
        }
        if (t.equals(q)) {
            score += EXACT_TITLE;
        } else if (contains(t, q)) {
            score += PHRASE_IN_TITLE;
        }
        return score;
    }

    private static boolean contains(String text, String part) {
        return !part.isEmpty() && (" " + text + " ").contains(part);
    }

    static String normal(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}

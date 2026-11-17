package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wortlautvergleich für Änderungseinheiten: Wörter, Ähnlichkeit über die längste gemeinsame Folge, Begriffe. */
final class TextComparison {

    private static final Pattern WORD =
            Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._/:-]*[\\p{L}\\p{N}]|[\\p{L}\\p{N}]");
    private static final Pattern NUMBER = Pattern.compile("\\p{N}[\\p{N}.,:/-]*");
    private static final Pattern ACRONYM = Pattern.compile("[\\p{Lu}\\p{N}]{2,}[\\p{Lu}\\p{N}-]*");
    /** Großgeschriebene Funktionswörter am Satzanfang, keine Begriffe. */
    private static final Set<String> STOP = Set.of("der", "die", "das", "dem", "den", "des", "ein", "eine", "einen",
            "einem", "einer", "eines", "und", "oder", "aber", "wenn", "dann", "für", "mit", "von", "bei", "nach", "aus",
            "auf", "als", "auch", "nicht", "kein", "keine", "sie", "wir", "ihr", "ich", "dieser", "diese", "dieses",
            "jeder", "jede", "jedes", "alle", "bitte", "hier", "dort", "zum", "zur", "im", "am", "an", "in", "ist",
            "sind", "wird", "werden", "the", "and", "for", "with");

    private TextComparison() {
    }

    /** Die Wörter eines Textes in Lesereihenfolge, normalisiert. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(TermText.normalize(matcher.group()));
        }
        return words;
    }

    /** Ähnlichkeit zweier Wortfolgen in [0,1]: zweimal die längste gemeinsame Folge durch die Summe der Längen. */
    static double similarity(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        return 2.0 * lcs(a, b) / (a.size() + b.size());
    }

    /** Die Wörter beider Seiten, die nicht in der längsten gemeinsamen Folge stehen. */
    static List<String> differing(List<String> a, List<String> b) {
        int[][] table = table(a, b);
        List<String> differing = new ArrayList<>();
        int i = a.size();
        int j = b.size();
        while (i > 0 && j > 0) {
            if (a.get(i - 1).equals(b.get(j - 1))) {
                i--;
                j--;
            } else if (table[i - 1][j] >= table[i][j - 1]) {
                differing.add(a.get(--i));
            } else {
                differing.add(b.get(--j));
            }
        }
        while (i > 0) {
            differing.add(a.get(--i));
        }
        while (j > 0) {
            differing.add(b.get(--j));
        }
        return differing;
    }

    /**
     * Ob sich zwei Wortfolgen nur in der Schreibweise unterscheiden: gleich viele entfallene wie hinzugekommene Wörter
     * außerhalb der gemeinsamen Folge, und jedes Paar in Reihenfolge zu mindestens {@value #SPELLING} gleich
     * (Zeichen-Editierabstand). „Post“ zu „E-Mail“ ist keine Schreibweise, „Intranett“ zu „Intranet“ schon.
     */
    static boolean spellingOnly(List<String> a, List<String> b) {
        int[][] table = table(a, b);
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        int i = a.size();
        int j = b.size();
        while (i > 0 && j > 0) {
            if (a.get(i - 1).equals(b.get(j - 1))) {
                i--;
                j--;
            } else if (table[i - 1][j] >= table[i][j - 1]) {
                removed.add(a.get(--i));
            } else {
                added.add(b.get(--j));
            }
        }
        while (i > 0) {
            removed.add(a.get(--i));
        }
        while (j > 0) {
            added.add(b.get(--j));
        }
        if (removed.isEmpty() || removed.size() != added.size()) {
            return false;
        }
        for (int k = 0; k < removed.size(); k++) {
            if (characterSimilarity(removed.get(k), added.get(k)) < SPELLING) {
                return false;
            }
        }
        return true;
    }

    /** Mindestähnlichkeit zweier Wörter, damit sie nur anders geschrieben gelten. */
    static final double SPELLING = 0.8;

    /** 1 − Editierabstand / Länge des längeren Worts. */
    static double characterSimilarity(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        int longer = Math.max(a.length(), b.length());
        return longer == 0 ? 1.0 : 1.0 - (double) previous[b.length()] / longer;
    }

    /** Ob ein Wort eine Zahl oder ein Datum ist. */
    static boolean number(String word) {
        return NUMBER.matcher(word).matches();
    }

    /**
     * Begriffe eines Textes, normalisiert und sortiert: großgeschriebene Wörter ab drei Zeichen (Substantive im
     * Deutschen) ohne Funktionswörter, und Akronyme.
     */
    static Set<String> terms(String text) {
        Set<String> terms = new TreeSet<>();
        if (text == null) {
            return terms;
        }
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            boolean acronym = ACRONYM.matcher(word).matches() && !number(word);
            boolean noun = word.length() >= 3 && Character.isUpperCase(word.codePointAt(0));
            String normalized = TermText.normalize(word);
            if ((acronym || noun) && !STOP.contains(normalized)) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private static int lcs(List<String> a, List<String> b) {
        return table(a, b)[a.size()][b.size()];
    }

    private static int[][] table(List<String> a, List<String> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                table[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? table[i - 1][j - 1] + 1
                        : Math.max(table[i - 1][j], table[i][j - 1]);
            }
        }
        return table;
    }
}

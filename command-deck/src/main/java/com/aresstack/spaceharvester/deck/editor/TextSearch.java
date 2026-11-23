package com.aresstack.spaceharvester.deck.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Suche im Editor wie die Suchleiste von MainframeMate ({@code FindBarPanel} mit {@code RegexFoldParser}): ein
 * regulärer Ausdruck ohne Unterscheidung von Groß- und Kleinschreibung; liefert die Fundstellen (zum Markieren und
 * schrittweisen Springen) und die Zeilen mit Treffer (zum Falten aller übrigen Zeilen).
 */
public final class TextSearch {

    /** Eine Fundstelle, Zeichenpositionen im Text ({@code end} exklusiv). */
    public record Match(int start, int end) {
    }

    private TextSearch() {
    }

    /** Warum {@code regex} kein gültiger Ausdruck ist; leer, wenn er gültig (oder leer) ist. */
    public static Optional<String> problem(String regex) {
        if (regex == null || regex.isBlank()) {
            return Optional.empty();
        }
        try {
            compile(regex);
            return Optional.empty();
        } catch (PatternSyntaxException e) {
            return Optional.of("Kein gültiger regulärer Ausdruck: " + e.getDescription());
        }
    }

    /** Alle Fundstellen; leere Treffer zählen nicht. Ungültig oder leer: keine. */
    public static List<Match> matches(String text, String regex) {
        List<Match> found = new ArrayList<>();
        if (text == null || regex == null || regex.isBlank() || problem(regex).isPresent()) {
            return found;
        }
        Matcher matcher = compile(regex).matcher(text);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                found.add(new Match(matcher.start(), matcher.end()));
            }
        }
        return List.copyOf(found);
    }

    /** Nummern (ab 0) der Zeilen, in denen der Ausdruck vorkommt. */
    public static List<Integer> matchingLines(String text, String regex) {
        List<Integer> lines = new ArrayList<>();
        if (text == null || regex == null || regex.isBlank() || problem(regex).isPresent()) {
            return lines;
        }
        Pattern pattern = compile(regex);
        String[] all = text.split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            if (pattern.matcher(all[i]).find()) {
                lines.add(i);
            }
        }
        return List.copyOf(lines);
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex.strip(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
    }
}

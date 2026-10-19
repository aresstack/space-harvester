package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Satz eines Absatzes mit seinen Wörtern, alles als Bereiche im Text der Projektion. Wörter sind für das Modell ab
 * 1 nummeriert; Satzzeichen sind keine Wörter.
 *
 * @param number 1-basierte Nummer im Absatz
 * @param span   Bereich des Satzes
 * @param text   Wortlaut des Satzes
 * @param words  die Wörter in Reihenfolge
 */
public record SentenceText(int number, TextSpan span, String text, List<TextSpan> words) {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'’.][\\p{L}\\p{N}]+)*");

    public SentenceText {
        words = List.copyOf(words);
    }

    /**
     * @param documentText der Text der Projektion
     * @param number       1-basierte Nummer im Absatz
     */
    static SentenceText of(String documentText, int number, TextSpan span) {
        List<TextSpan> words = new ArrayList<>();
        Matcher word = WORD.matcher(documentText).region(span.start(), span.end());
        while (word.find()) {
            words.add(new TextSpan(word.start(), word.end()));
        }
        return new SentenceText(number, span, span.of(documentText), words);
    }

    /** Der Bereich von Wort {@code first} bis Wort {@code last} (ab 1, einschließlich) oder {@code null}. */
    public TextSpan wordRange(int first, int last) {
        if (first < 1 || last < first || last > words.size()) {
            return null;
        }
        return new TextSpan(words.get(first - 1).start(), words.get(last - 1).end());
    }

    /** Ob {@code inner} in diesem Satz liegt. */
    public boolean contains(TextSpan inner) {
        return inner.start() >= span.start() && inner.end() <= span.end();
    }

    /** Der Satz mit nummerierten Wörtern, wie das Modell ihn sieht: {@code [1]Die [2]zuständige ...}. */
    String numbered(String documentText) {
        StringBuilder out = new StringBuilder();
        int cursor = span.start();
        for (int i = 0; i < words.size(); i++) {
            TextSpan word = words.get(i);
            out.append(documentText, cursor, word.start()).append('[').append(i + 1).append(']')
                    .append(documentText, word.start(), word.end());
            cursor = word.end();
        }
        out.append(documentText, cursor, span.end());
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    @Override
    public String toString() {
        // Ohne Wortlaut: toString landet leicht im Log.
        return "SentenceText[" + number + " " + span + ", " + words.size() + " Wörter]";
    }
}

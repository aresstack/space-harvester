package com.aresstack.spaceharvester.application.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verankert ein Zitat im Originaltext (ADR 0035, Abschnitt 3; aus {@code StatementValidator} herausgelöst, Regeln
 * unverändert): Der vermutete Bereich gilt, wenn er den zitierten Wortlaut trägt; sonst gilt das einzige an
 * Wortgrenzen ausgerichtete Vorkommen im Suchfenster. Zweimal vorhanden ist mehrdeutig, gar nicht vorhanden wird
 * verworfen; nie wird geraten. Verglichen wird nach {@link #comparable}.
 */
public final class TextGrounding {

    /** Längstes Zitat in Wörtern für {@link #ground(String, TextSpan, String, TextSpan)}. */
    public static final int MAX_QUOTE_WORDS = 80;

    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final String EDGE = "\"'„“”‚‘’«»()[]{}.,;:!?";

    private TextGrounding() {
    }

    /** Ergebnis einer Verankerung. */
    public sealed interface Result permits Grounded, Rejected {
    }

    /** Verankert: Bereich und Wortlaut im Text. */
    public record Grounded(GroundedText text) implements Result {

        public Grounded {
            Objects.requireNonNull(text, "text");
        }
    }

    /** Nicht verankert, mit Grund. */
    public record Rejected(Reason reason) implements Result {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Warum ein Zitat nicht verankert ist. */
    public enum Reason {
        /** Weder Zitat noch vermuteter Bereich. */
        MISSING,
        /** Das Zitat steht nicht im Suchfenster. */
        NOT_FOUND,
        /** Das Zitat steht mehrmals im Suchfenster. */
        AMBIGUOUS
    }

    /**
     * Verankert {@code quote} im Fenster {@code window} von {@code text}; die Wörter sind die Nicht-Leerraum-Folgen des
     * Fensters.
     *
     * @param hint vermuteter Bereich oder {@code null}; zählt nur, wenn er im Fenster liegt
     */
    public static Result ground(String text, TextSpan hint, String quote, TextSpan window) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(window, "window");
        TextSpan bounded = new TextSpan(Math.min(window.start(), text.length()), Math.min(window.end(), text.length()));
        List<TextSpan> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text).region(bounded.start(), bounded.end());
        while (matcher.find()) {
            words.add(new TextSpan(matcher.start(), matcher.end()));
        }
        TextSpan inside = hint != null && hint.start() >= bounded.start() && hint.end() <= bounded.end() ? hint : null;
        return ground(text, inside, quote, words, MAX_QUOTE_WORDS);
    }

    /**
     * Verankert {@code quote} an den gegebenen Wortgrenzen (so prüft {@code StatementValidator} je Satz).
     *
     * @param hint     vermuteter Bereich oder {@code null}
     * @param words    Wortbereiche in Lesereihenfolge
     * @param maxWords längstes betrachtetes Vorkommen in Wörtern
     */
    public static Result ground(String text, TextSpan hint, String quote, List<TextSpan> words, int maxWords) {
        String wanted = comparable(quote);
        if (hint != null && hint.end() <= text.length()
                && (wanted.isEmpty() || comparable(hint.of(text)).equals(wanted))) {
            return new Grounded(new GroundedText(hint, hint.of(text)));
        }
        if (wanted.isEmpty()) {
            return new Rejected(Reason.MISSING);
        }
        List<TextSpan> hits = new ArrayList<>();
        for (int first = 0; first < words.size(); first++) {
            for (int last = first; last < words.size() && last - first < maxWords; last++) {
                TextSpan candidate = new TextSpan(words.get(first).start(), words.get(last).end());
                if (comparable(candidate.of(text)).equals(wanted)) {
                    hits.add(candidate);
                }
            }
        }
        if (hits.size() == 1) {
            return new Grounded(new GroundedText(hits.getFirst(), hits.getFirst().of(text)));
        }
        return new Rejected(hits.isEmpty() ? Reason.NOT_FOUND : Reason.AMBIGUOUS);
    }

    /**
     * Vergleichstext: Unicode NFC, Kleinbuchstaben (ohne Locale), innerer Leerraum zusammengefasst, Anführungs- und
     * Satzzeichen am Rand entfernt.
     */
    public static String comparable(String text) {
        if (text == null) {
            return "";
        }
        String value = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        int start = 0;
        int end = value.length();
        while (start < end && EDGE.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end).trim();
    }
}

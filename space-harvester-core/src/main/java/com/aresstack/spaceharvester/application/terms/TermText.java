package com.aresstack.spaceharvester.application.terms;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suchwerte für Begriffe. Ein Suchwert dient dem Finden, nie als Identität: Er faltet nur Unicode-Form,
 * Groß-/Kleinschreibung und Leerraum, nie Flexion, Umlaute oder Bindestriche ({@code Beleg} und {@code Belege}
 * bleiben zwei Begriffe, {@code Rückmeldung} wird nie {@code rueckmeldung}).
 */
public final class TermText {

    private TermText() {
    }

    /** Unicode NFC, Kleinbuchstaben (unabhängig vom Gebietsschema), Leerraum zu einem Leerzeichen. */
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .trim();
    }

    /** Die Wörter eines Suchwerts, getrennt an Leerzeichen und Bindestrichen, für die Präfixsuche je Wort. */
    public static List<String> words(String normalized) {
        List<String> words = new ArrayList<>();
        for (String word : normalized.split("[ \\-]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /** Ob ein Wortlaut als Begriff taugt: nicht leer, ohne Steuerzeichen, höchstens 200 Zeichen. */
    public static boolean usable(String term) {
        if (term.isBlank() || term.length() > 200) {
            return false;
        }
        return term.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }
}

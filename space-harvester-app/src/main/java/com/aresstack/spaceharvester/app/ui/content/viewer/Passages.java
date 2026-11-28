package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.awt.Color;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Findet eine Textstelle (etwa die Fundstelle einer Extraktion) im gezeigten Text eines Dokuments. Zuerst über den
 * wörtlichen Auszug, unempfindlich gegen Zeilenumbrüche, Mehrfach-Leerzeichen und Groß-/Kleinschreibung, denn die
 * Vorschau bricht Zeilen anders als der Text, aus dem der Auszug stammt; erst ohne Auszug über den Zeichenbereich.
 * Ohne Swing, damit es sich ohne Fenster prüfen lässt.
 */
public final class Passages {

    /** Die Farbe der markierten Fundstelle: durchscheinendes Orange, der Text bleibt lesbar. */
    public static final Color MARK = AppColors.of("app.Passages.mark");

    private Passages() {
    }

    /**
     * Die Stelle als {@code {start, end}} im {@code text}; {@code null}, wenn sie sich nicht finden lässt.
     *
     * @param excerpt wörtlicher Auszug, leer ohne
     * @param start   Anfang im extrahierten Text, {@code -1} unbekannt
     * @param end     Ende (exklusiv), {@code -1} unbekannt
     */
    public static int[] locate(String text, String excerpt, int start, int end) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String wanted = excerpt == null ? "" : excerpt.strip();
        if (!wanted.isEmpty()) {
            Matcher found = pattern(wanted).matcher(text);
            if (found.find()) {
                return new int[] {found.start(), found.end()};
            }
            return null;
        }
        if (start >= 0 && end > start && end <= text.length()) {
            return new int[] {start, end};
        }
        return null;
    }

    /** Wörter des Auszugs in Reihenfolge, dazwischen beliebiger Leerraum; typografische Anführungszeichen tolerant. */
    private static Pattern pattern(String excerpt) {
        String[] words = excerpt.strip().split("\\s+");
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(words[i].toLowerCase(Locale.ROOT)));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}

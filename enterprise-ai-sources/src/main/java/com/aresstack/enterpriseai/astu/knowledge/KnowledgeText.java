package com.aresstack.enterpriseai.astu.knowledge;

/**
 * Leerraum-Regeln des Knowledge-Modells. {@link String#trim()} kennt nur Zeichen bis U+0020; hier zählen auch
 * Unicode-Leerzeichen wie das geschützte Leerzeichen (U+00A0), das bei HTML-Extraktion häufig übrig bleibt.
 * Welche Codepunkte Leerraum sind, legt {@link UnicodeClasses} fest, nicht das laufende JDK.
 */
final class KnowledgeText {

    private KnowledgeText() {
    }

    static boolean isSpace(int codePoint) {
        return UnicodeClasses.isSpace(codePoint);
    }

    static boolean isBlank(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!isSpace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** {@code true}, wenn der Text mit Leerraum (inkl. Unicode-Leerzeichen) beginnt oder endet. */
    static boolean hasEdgeSpace(String text) {
        return !text.isEmpty() && (isSpace(text.codePointAt(0)) || isSpace(text.codePointBefore(text.length())));
    }

    /**
     * Ersetzt Unicode-Leerzeichen durch {@code ' '} und Zeilen-/Absatztrenner (U+0085, U+2028, U+2029) durch
     * {@code '\n'}; ASCII-Leerraum (Tabulator, Zeilenumbruch ...) bleibt.
     */
    static String unifySpaces(String text) {
        StringBuilder unified = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char replacement = c;
            if (c == '\u0085' || c == ' ' || c == ' ') {
                replacement = '\n';
            } else if (c >= 0x80 && isSpace(c)) {
                replacement = ' ';
            }
            if (replacement != c && unified == null) {
                unified = new StringBuilder(text);
            }
            if (unified != null) {
                unified.setCharAt(i, replacement);
            }
        }
        return unified == null ? text : unified.toString();
    }
}

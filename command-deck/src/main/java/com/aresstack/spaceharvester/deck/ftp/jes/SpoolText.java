package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.Locale;

/**
 * Aufbereitung von Spool-Text für die Anzeige, übernommen aus MainframeMate ({@code JobDetailTab}): Steuerzeichen
 * entfernen, JESJCL zu wieder einreichbarer JCL bereinigen und die Sprache für die Hervorhebung erkennen.
 */
public final class SpoolText {

    /** Sprache der Hervorhebung, wie die Auswahl „Auto / JCL / COBOL / NATURAL / Plain Text“. */
    public enum Language {
        AUTO("Auto"), JCL("JCL"), COBOL("COBOL"), NATURAL("NATURAL"), PLAIN("Plain Text");

        private final String label;

        Language(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final String JCL_START = "//";
    private static final String[] JCL_KEYWORDS = {"JOB", "EXEC", "DD", "PROC", "PEND", "SET", "JCLLIB", "INCLUDE"};
    private static final int NORMAL = 0;
    private static final int INSTREAM = 1;
    private static final int SUBSTITUTION = 2;

    private SpoolText() {
    }

    /** Wie die Anzeige von MainframeMate: erst Steuerzeichen weg, dann JESJCL bereinigen. */
    public static String clean(String raw) {
        return normalizeJcl(sanitize(raw));
    }

    /**
     * Entfernt Steuerzeichen, an denen Anzeige und Zwischenablage scheitern: Seitenvorschub wird Zeilenumbruch,
     * Nullbytes und andere Steuerzeichen fallen weg; Zeilenumbruch und Tabulator bleiben.
     */
    public static String sanitize(String content) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        StringBuilder text = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                text.append(c);
            } else if (c == '\f') {
                text.append('\n');
            } else if (c >= 0x20) {
                text.append(c);
            }
        }
        return text.toString();
    }

    /**
     * Macht aus JESJCL-Spool (10 Zeichen Anweisungsnummer vor jeder Zeile) wieder einreichbare JCL: {@code XX} und
     * {@code X/} (Prozedur-Auflösung) sowie IEF-Meldungen fallen weg, Instream-Daten bleiben, die JES-Jobnummer auf der
     * JOB-Karte wird entfernt. Sieht der Text nicht nach JES-Spool aus, bleibt er unverändert.
     */
    public static String normalizeJcl(String content) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length < 2 || !looksLikeJesSpool(lines)) {
            return content;
        }
        int state = NORMAL;
        boolean instreamHadData = false;
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            String stripped = line.length() > 10 ? line.substring(10) : line.length() == 10 ? "" : line.trim();
            if (state == SUBSTITUTION) {
                if (isRecognisedPrefix(stripped)) {
                    state = NORMAL;
                } else {
                    continue;
                }
            }
            if (state == INSTREAM) {
                if (stripped.startsWith("/*")) {
                    if (!first) {
                        out.append('\n');
                    }
                    first = false;
                    out.append(rtrim(stripped));
                    state = NORMAL;
                    continue;
                }
                if (isRecognisedPrefix(stripped)) {
                    if (!instreamHadData) {
                        out.append('\n').append("//* << instream data omitted by JES2 >>");
                    }
                    state = NORMAL;
                } else {
                    instreamHadData = true;
                    if (!first) {
                        out.append('\n');
                    }
                    first = false;
                    out.append(rtrim(stripped));
                    continue;
                }
            }
            if (stripped.startsWith("XX") || stripped.startsWith("X/")) {
                continue;
            }
            if (stripped.startsWith("IEF")) {
                state = SUBSTITUTION;
                continue;
            }
            if (stripped.startsWith("//") || stripped.startsWith("/*")) {
                if (!first) {
                    out.append('\n');
                }
                first = false;
                String statement = stripJobNumber(rtrim(stripped));
                out.append(statement);
                if (isInstreamDataDd(statement)) {
                    state = INSTREAM;
                    instreamHadData = false;
                }
            }
        }
        String result = out.toString();
        while (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isRecognisedPrefix(String stripped) {
        return stripped.startsWith("//") || stripped.startsWith("XX") || stripped.startsWith("X/")
                || stripped.startsWith("IEF");
    }

    /** Ob eine JCL-Zeile Instream-Daten ankündigt ({@code DD *} oder {@code DD DATA}). */
    public static boolean isInstreamDataDd(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        int dd = upper.indexOf(" DD ");
        if (dd < 0) {
            return false;
        }
        String after = upper.substring(dd + 4).trim();
        return after.startsWith("*") || after.startsWith("DATA");
    }

    /** Entfernt die JES-Jobnummer ({@code Jnnnnnnn} in Spalte 73–80) von der JOB-Karte. */
    public static String stripJobNumber(String line) {
        if (line.length() < 20) {
            return line;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        if (!upper.contains(" JOB ") && !upper.contains(" JOB(")) {
            return line;
        }
        String trimmed = rtrim(line);
        if (trimmed.length() >= 8 && trimmed.substring(trimmed.length() - 8).matches("J\\d{7}")) {
            return rtrim(trimmed.substring(0, trimmed.length() - 8));
        }
        return line;
    }

    /** Ob die Zeilen dem JESJCL-Format folgen (nummerierte Anweisungen, XX-/IEFC-Zeilen). */
    public static boolean looksLikeJesSpool(String[] lines) {
        int numbered = 0;
        int expansions = 0;
        int substitutions = 0;
        int limit = Math.min(lines.length, 500);
        for (int i = 0; i < limit; i++) {
            String line = lines[i];
            if (line.length() < 12) {
                continue;
            }
            String prefix = line.substring(0, 10);
            String after = line.substring(10);
            if (prefix.charAt(9) == ' ' && prefix.substring(0, 9).trim().matches("\\d+")
                    && (after.startsWith("//") || after.startsWith("XX"))) {
                numbered++;
            }
            if (after.startsWith("XX") || after.startsWith("X/")) {
                expansions++;
            }
            if (after.startsWith("IEFC")) {
                substitutions++;
            }
            if (numbered >= 3 || (numbered >= 1 && expansions + substitutions >= 2)) {
                return true;
            }
        }
        return numbered >= 3;
    }

    /**
     * Die Sprache für die Hervorhebung: eine feste Wahl gilt immer; bei „Auto“ ist JESJCL (und {@code *.JCL}) JCL,
     * sonst entscheidet der Inhalt (Natural vor JCL vor COBOL), sonst einfacher Text.
     */
    public static Language detect(String content, String ddName, Language chosen) {
        if (chosen != null && chosen != Language.AUTO) {
            return chosen;
        }
        if (content == null || content.isEmpty()) {
            return Language.PLAIN;
        }
        if (ddName != null) {
            String upper = ddName.toUpperCase(Locale.ROOT);
            if (upper.equals("JESJCL") || upper.endsWith(".JCL")) {
                return Language.JCL;
            }
        }
        if (isNatural(content)) {
            return Language.NATURAL;
        }
        if (isJcl(content)) {
            return Language.JCL;
        }
        if (isCobol(content)) {
            return Language.COBOL;
        }
        return Language.PLAIN;
    }

    static boolean isJcl(String content) {
        if (content == null || content.length() < 3) {
            return false;
        }
        int jclLines = 0;
        int keywords = 0;
        for (String line : content.split("\\r?\\n", 80)) {
            String trimmed = line.trim();
            String statement = trimmed.startsWith(JCL_START) ? trimmed : trimmed.replaceFirst("^\\d+\\s+", "");
            if (!statement.startsWith(JCL_START)) {
                continue;
            }
            jclLines++;
            String upper = statement.toUpperCase(Locale.ROOT);
            for (String keyword : JCL_KEYWORDS) {
                if (upper.contains(" " + keyword + " ") || upper.contains(" " + keyword + ",")
                        || upper.contains("//" + keyword + " ")) {
                    keywords++;
                    break;
                }
            }
        }
        return jclLines >= 2 && keywords >= 1;
    }

    static boolean isCobol(String content) {
        String upper = content.toUpperCase(Locale.ROOT);
        return upper.contains("IDENTIFICATION DIVISION") || upper.contains("PROCEDURE DIVISION")
                || upper.contains("DATA DIVISION") || upper.contains("WORKING-STORAGE SECTION");
    }

    static boolean isNatural(String content) {
        int hits = 0;
        for (String line : content.split("\\r?\\n", 40)) {
            String trimmed = line.trim().toUpperCase(Locale.ROOT);
            if (trimmed.startsWith("DEFINE DATA") || trimmed.startsWith("END-DEFINE")
                    || trimmed.startsWith("DEFINE SUBROUTINE") || trimmed.startsWith("CALLNAT ")
                    || trimmed.startsWith("END-SUBROUTINE") || trimmed.startsWith("LOCAL USING")
                    || trimmed.startsWith("PARAMETER USING") || trimmed.startsWith("DECIDE ON")
                    || trimmed.startsWith("DECIDE FOR") || trimmed.startsWith("INPUT USING MAP")
                    || trimmed.startsWith("FETCH RETURN")) {
                hits++;
            }
        }
        return hits >= 2;
    }

    /** Fundstellen einer Suche ohne Rücksicht auf Groß-/Kleinschreibung (für „n Treffer“). */
    public static int count(String text, String query) {
        if (text == null || query == null || query.isEmpty()) {
            return 0;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String rtrim(String text) {
        int length = text.length();
        while (length > 0 && text.charAt(length - 1) <= ' ') {
            length--;
        }
        return text.substring(0, length);
    }
}

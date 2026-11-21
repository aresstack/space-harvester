package com.aresstack.spaceharvester.app.ui.markdown;

/**
 * Macht Blockmarken am Zeilenanfang für CommonMark lesbar, wenn das Modell dahinter ein typografisches Leerzeichen
 * setzt.
 *
 * <p>CommonMark erkennt eine Überschrift ({@code ## Titel}), einen Listenpunkt ({@code - Punkt}, {@code 1. Punkt}) nur,
 * wenn nach der Marke ein ASCII-Leerzeichen oder Tab steht. Modelle wie gpt-oss setzen dort gelegentlich ein
 * geschütztes oder schmales Leerzeichen (U+00A0, U+202F, …) — dieselben Zeichen, die sie auch zwischen Wörtern
 * verwenden. Flexmark liest die Zeile dann als Absatz: Fett und Kursiv erscheinen, die Marke {@code ##} bleibt als
 * Text stehen, und das nur in den Antworten, in denen das Modell dieses Zeichen gewählt hat.</p>
 *
 * <p>Ersetzt wird ausschließlich das eine Zeichen direkt nach einer Blockmarke (und unsichtbare Zeichen bzw.
 * Sonderleerzeichen in der Einrückung davor) durch ein ASCII-Leerzeichen, außerhalb von Codezäunen. Die Länge des
 * Textes bleibt gleich, daher stimmen die Quellpositionen der Blöcke ({@link MarkdownMessageView#SOURCE_START})
 * weiterhin mit dem Originaltext überein.</p>
 */
final class BlockMarkerSpacing {

    /** CommonMark: bis zu drei Leerzeichen Einrückung, darüber ist es ein eingerückter Codeblock. */
    private static final int MAX_MARKER_INDENT = 3;
    /** CommonMark: geordnete Listenmarken haben höchstens neun Ziffern. */
    private static final int MAX_ORDERED_DIGITS = 9;
    /** CommonMark: Überschriften haben höchstens sechs Rauten. */
    private static final int MAX_HEADING_LEVEL = 6;

    private BlockMarkerSpacing() {
    }

    static String normalize(String markdown) {
        if (markdown == null || !containsSpecialSpace(markdown)) {
            return markdown;
        }
        char[] chars = markdown.toCharArray();
        String openFence = null;
        int lineStart = 0;
        while (lineStart <= chars.length) {
            int lineEnd = lineStart;
            while (lineEnd < chars.length && chars[lineEnd] != '\n') {
                lineEnd++;
            }
            String fence = fenceMarker(chars, lineStart, lineEnd);
            if (openFence != null) {
                if (fence != null && fence.charAt(0) == openFence.charAt(0) && fence.length() >= openFence.length()
                        && isBlankAfter(chars, lineStart, lineEnd, fence)) {
                    openFence = null;
                }
            } else if (fence != null) {
                openFence = fence;
            } else {
                normalizeLine(chars, lineStart, lineEnd);
            }
            lineStart = lineEnd + 1;
        }
        return new String(chars);
    }

    private static void normalizeLine(char[] chars, int start, int end) {
        int position = start;
        int indent = 0;
        // Unsichtbare Zeichen oder Sonderleerzeichen in der Einrückung zählen wie Leerzeichen, solange es höchstens
        // drei sind; sonst bliebe die Zeile ein Absatz statt einer Überschrift.
        while (position < end && indent <= MAX_MARKER_INDENT
                && (chars[position] == ' ' || isSpecialSpace(chars[position]))) {
            position++;
            indent++;
        }
        if (indent > MAX_MARKER_INDENT || position >= end) {
            return;
        }
        int afterMarker = markerEnd(chars, position, end);
        if (afterMarker < 0 || afterMarker >= end || !isSpecialSpace(chars[afterMarker])) {
            return;
        }
        for (int i = start; i < position; i++) {
            chars[i] = ' ';
        }
        chars[afterMarker] = ' ';
    }

    /** @return Position direkt hinter einer Blockmarke ab {@code position}, sonst {@code -1} */
    private static int markerEnd(char[] chars, int position, int end) {
        char first = chars[position];
        if (first == '#') {
            int hashes = position;
            while (hashes < end && chars[hashes] == '#') {
                hashes++;
            }
            return hashes - position <= MAX_HEADING_LEVEL ? hashes : -1;
        }
        if (first == '-' || first == '*' || first == '+') {
            return position + 1;
        }
        int digits = position;
        while (digits < end && chars[digits] >= '0' && chars[digits] <= '9') {
            digits++;
        }
        int count = digits - position;
        if (count > 0 && count <= MAX_ORDERED_DIGITS && digits < end
                && (chars[digits] == '.' || chars[digits] == ')')) {
            return digits + 1;
        }
        return -1;
    }

    /** Die Zaunmarke ({@code ```} oder {@code ~~~}, mindestens drei) am Zeilenanfang, sonst {@code null}. */
    private static String fenceMarker(char[] chars, int start, int end) {
        int position = start;
        int indent = 0;
        while (position < end && chars[position] == ' ' && indent < MAX_MARKER_INDENT) {
            position++;
            indent++;
        }
        if (position >= end || (chars[position] != '`' && chars[position] != '~')) {
            return null;
        }
        char marker = chars[position];
        int run = position;
        while (run < end && chars[run] == marker) {
            run++;
        }
        return run - position >= 3 ? new String(chars, position, run - position) : null;
    }

    private static boolean isBlankAfter(char[] chars, int start, int end, String fence) {
        String line = new String(chars, start, end - start).trim();
        return line.length() >= fence.length() && line.substring(fence.length()).replace(fence.substring(0, 1), "")
                .trim().isEmpty();
    }

    private static boolean containsSpecialSpace(String markdown) {
        for (int i = 0; i < markdown.length(); i++) {
            if (isSpecialSpace(markdown.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Leerzeichen außer ASCII-Leerzeichen und Tab (geschützte, schmale, typografische Leerzeichen) sowie unsichtbare
     * Formatzeichen (Nullbreite, Wortverbinder, BOM).
     */
    static boolean isSpecialSpace(char c) {
        if (c == ' ' || c == '\t') {
            return false;
        }
        return Character.isSpaceChar(c) || c == '​' || c == '⁠' || c == '﻿';
    }
}

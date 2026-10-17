package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Konservative Textprojektion des Confluence-Storage-Formats ({@value #SCHEMA}): nur sichtbarer Fließtext.
 *
 * <ul>
 *   <li>Makros ({@code ac:structured-macro}, {@code ac:macro}) samt Parametern und Inhalt, Bilder, Emoticons,
 *       Platzhalter und alle Ressourcenverweise ({@code ri:*}) entfallen vollständig: nichts wird ausgeführt,
 *       aufgelöst, nachgeladen oder eingebettet. Dazu zählen auch Code-, Diagramm- und Include-Makros; ihre
 *       Auswertung ist Sache eigener Prozessoren (#19).</li>
 *   <li>Linktexte ({@code ac:link-body}, {@code ac:plain-text-link-body}) bleiben, ihr Ziel nicht.</li>
 *   <li>Blockelemente trennen Zeilen, Tabellenzellen werden durch {@code " | "} getrennt; Leerraum wird zu einem
 *       Leerzeichen zusammengefasst.</li>
 *   <li>Überschriften {@code h1}–{@code h6} begrenzen Abschnitte.</li>
 * </ul>
 *
 * <p>Der Parser ist fehlertolerant (Storage ist nicht immer wohlgeformt) und baut kein DOM; DTDs, externe Entitäten
 * und Processing Instructions werden übersprungen, nie ausgewertet.
 */
final class ConfluenceStorageText {

    static final String SCHEMA = "confluence-storage-text/v1";

    private static final Set<String> SUPPRESSED = Set.of("ac:structured-macro", "ac:macro", "ac:image",
            "ac:emoticon", "ac:placeholder", "ac:parameter", "ac:task-id", "ac:task-status", "ac:inline-comment-marker-ref",
            "script", "style", "time");
    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "ul", "ol", "tr", "table", "tbody", "thead",
            "br", "hr", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "ac:task", "ac:task-list",
            "ac:layout", "ac:layout-section", "ac:layout-cell", "dl", "dt", "dd");
    private static final Set<String> CELLS = Set.of("td", "th");
    private static final Map<String, String> ENTITIES = Map.ofEntries(Map.entry("amp", "&"), Map.entry("lt", "<"),
            Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("hellip", "…"), Map.entry("auml", "ä"),
            Map.entry("ouml", "ö"), Map.entry("uuml", "ü"), Map.entry("Auml", "Ä"), Map.entry("Ouml", "Ö"),
            Map.entry("Uuml", "Ü"), Map.entry("szlig", "ß"), Map.entry("euro", "€"), Map.entry("laquo", "«"),
            Map.entry("raquo", "»"), Map.entry("bdquo", "„"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("bull", "•"), Map.entry("middot", "·"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("deg", "°"));

    private final String storage;
    private final StringBuilder out = new StringBuilder();
    private final Deque<String> open = new ArrayDeque<>();
    private final List<int[]> headings = new ArrayList<>();
    private final List<String> headingTexts = new ArrayList<>();
    private int suppressed;
    private int cells;
    private int headingStart = -1;
    private int headingLevel;
    private boolean pendingSpace;

    private ConfluenceStorageText(String storage) {
        this.storage = storage;
    }

    static PageText project(String storage) {
        return new ConfluenceStorageText(storage).run();
    }

    private PageText run() {
        int i = 0;
        int length = storage.length();
        while (i < length) {
            char c = storage.charAt(i);
            if (c == '<') {
                i = markup(i);
            } else if (c == '&') {
                i = entity(i);
            } else {
                text(String.valueOf(c));
                i++;
            }
        }
        newline();
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        String text = out.toString();
        List<PageText.Section> sections = new ArrayList<>();
        for (int h = 0; h < headings.size(); h++) {
            int start = Math.min(headings.get(h)[0], text.length());
            int end = h + 1 < headings.size() ? Math.min(headings.get(h + 1)[0], text.length()) : text.length();
            sections.add(new PageText.Section(headingTexts.get(h), headings.get(h)[1], start, Math.max(start, end)));
        }
        return new PageText(SCHEMA, text, sections);
    }

    private int markup(int i) {
        if (storage.startsWith("<![CDATA[", i)) {
            int end = storage.indexOf("]]>", i + 9);
            int stop = end < 0 ? storage.length() : end;
            text(storage.substring(i + 9, stop));
            return end < 0 ? storage.length() : end + 3;
        }
        if (storage.startsWith("<!--", i)) {
            return skipPast(i + 4, "-->");
        }
        if (storage.startsWith("<!", i)) {
            return declarationEnd(i + 2);
        }
        if (storage.startsWith("<?", i)) {
            return skipPast(i + 2, ">");
        }
        int end = tagEnd(i + 1);
        if (end < 0) {
            text("<");
            return i + 1;
        }
        String tag = storage.substring(i + 1, end);
        boolean closing = tag.startsWith("/");
        boolean selfClosing = !closing && tag.endsWith("/");
        String name = name(closing ? tag.substring(1) : tag);
        if (name.isEmpty()) {
            text("<");
            return i + 1;
        }
        if (closing) {
            close(name);
        } else {
            openElement(name, selfClosing);
        }
        return end + 1;
    }

    private void openElement(String name, boolean selfClosing) {
        boolean hidden = SUPPRESSED.contains(name) || name.startsWith("ri:");
        if (suppressed == 0 && !hidden) {
            if (BLOCKS.contains(name)) {
                newline();
            } else if (CELLS.contains(name)) {
                cell();
            }
            if (!selfClosing && isHeading(name)) {
                headingStart = out.length();
                headingLevel = name.charAt(1) - '0';
            }
        }
        if (selfClosing) {
            return;
        }
        open.push(name);
        if (CELLS.contains(name)) {
            cells++;
        }
        if (hidden) {
            suppressed++;
        }
    }

    private void close(String name) {
        if (!open.contains(name)) {
            return;
        }
        while (!open.isEmpty()) {
            String top = open.pop();
            if (CELLS.contains(top)) {
                cells--;
            }
            if (SUPPRESSED.contains(top) || top.startsWith("ri:")) {
                suppressed--;
            }
            if (suppressed == 0 && isHeading(top) && headingStart >= 0) {
                String heading = out.substring(Math.min(headingStart, out.length())).trim();
                if (!heading.isEmpty()) {
                    headings.add(new int[] {headingStart, headingLevel});
                    headingTexts.add(heading);
                }
                headingStart = -1;
            }
            if (suppressed == 0 && (BLOCKS.contains(top) || CELLS.contains(top))) {
                newlineIfBlock(top);
            }
            if (top.equals(name)) {
                return;
            }
        }
    }

    private void newlineIfBlock(String name) {
        if (BLOCKS.contains(name)) {
            newline();
        } else {
            pendingSpace = true;
        }
    }

    private static boolean isHeading(String name) {
        return name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6';
    }

    private int entity(int i) {
        int end = storage.indexOf(';', i + 1);
        if (end < 0 || end - i > 12) {
            text("&");
            return i + 1;
        }
        String ref = storage.substring(i + 1, end);
        String value = null;
        try {
            if (ref.startsWith("#x") || ref.startsWith("#X")) {
                value = Character.toString(Integer.parseInt(ref.substring(2), 16));
            } else if (ref.startsWith("#")) {
                value = Character.toString(Integer.parseInt(ref.substring(1)));
            } else {
                value = ENTITIES.get(ref);
            }
        } catch (IllegalArgumentException e) {
            value = null;
        }
        if (value == null) {
            // Unbekannte benannte Entität: weder auflösen noch als Markup durchreichen.
            pendingSpace = pendingSpace || out.length() > 0;
            return end + 1;
        }
        text(value);
        return end + 1;
    }

    private void text(String value) {
        if (suppressed > 0) {
            return;
        }
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = true;
            } else if (Character.isISOControl(c)) {
                continue;
            } else {
                char last = out.isEmpty() ? '\n' : out.charAt(out.length() - 1);
                if (pendingSpace && last != '\n' && last != ' ') {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
            }
        }
    }

    private void cell() {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append(" | ");
        }
        pendingSpace = false;
    }

    private void newline() {
        if (cells > 0) {
            // Blöcke innerhalb einer Tabellenzelle bleiben in der Zeile ihrer Tabellenzeile.
            pendingSpace = true;
            return;
        }
        trimEnd();
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        pendingSpace = false;
    }

    private void trimEnd() {
        int length = out.length();
        while (length > 0 && (out.charAt(length - 1) == ' '
                || out.charAt(length - 1) == '|' && length > 1 && out.charAt(length - 2) == ' ')) {
            length--;
        }
        if (length < out.length() && headingStart > length) {
            headingStart = length;
        }
        out.setLength(length);
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n' && headingStart > out.length()) {
            headingStart = out.length();
        }
    }

    /** Ende einer Deklaration wie {@code <!DOCTYPE x [ … ]>}; eine interne Teilmenge wird übersprungen. */
    private int declarationEnd(int from) {
        int close = storage.indexOf('>', from);
        int subset = storage.indexOf('[', from);
        if (subset >= 0 && (close < 0 || subset < close)) {
            int end = storage.indexOf("]>", subset);
            return end < 0 ? storage.length() : end + 2;
        }
        return close < 0 ? storage.length() : close + 1;
    }

    private int skipPast(int from, String end) {
        int found = storage.indexOf(end, from);
        return found < 0 ? storage.length() : found + end.length();
    }

    /** Position des schließenden {@code >} eines Tags, Anführungszeichen in Attributen beachtend. */
    private int tagEnd(int from) {
        char quote = 0;
        for (int k = from; k < storage.length(); k++) {
            char c = storage.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return k;
            } else if (c == '<') {
                return -1;
            }
        }
        return -1;
    }

    private static String name(String tag) {
        int k = 0;
        while (k < tag.length() && !Character.isWhitespace(tag.charAt(k)) && tag.charAt(k) != '/') {
            k++;
        }
        return tag.substring(0, k).toLowerCase(Locale.ROOT);
    }
}

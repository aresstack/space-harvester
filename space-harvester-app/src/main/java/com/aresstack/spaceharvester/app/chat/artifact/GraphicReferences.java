package com.aresstack.spaceharvester.app.chat.artifact;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verweise auf Grafik-Artefakte in einem Dokument (ADR 0045, Abschnitt 6): {@code ![Titel](artefakt:G1)}. Das Dokument
 * enthält nie eine Kopie des SVG; Anzeige und Export lösen den Verweis auf, sodass eine korrigierte Grafik im
 * Dokument von selbst stimmt.
 */
public final class GraphicReferences {

    private static final Pattern REFERENCE = Pattern.compile(
            "!\\[([^\\]\\n]*)]\\(" + Pattern.quote(DocumentChatTools.LINK_SCHEME) + "(G\\d{1,6})\\)",
            Pattern.CASE_INSENSITIVE);

    private GraphicReferences() {
    }

    /** Der Verweis auf eine Grafik, wie er im Dokument steht. */
    public static String reference(ChatArtifact graphic) {
        return "![" + graphic.title().replace("]", ")") + "](" + DocumentChatTools.LINK_SCHEME + graphic.id() + ")";
    }

    /** Die Kennungen der Grafiken, auf die {@code markdown} verweist, in der Reihenfolge des ersten Vorkommens. */
    public static Set<String> ids(String markdown) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher match = REFERENCE.matcher(markdown == null ? "" : markdown);
        while (match.find()) {
            ids.add(match.group(2).toUpperCase(Locale.ROOT));
        }
        return ids;
    }

    /**
     * Ersetzt jeden Verweis durch den SVG-Block, den die Markdown-Anzeige als Bild zeigt; eine fehlende Grafik wird zu
     * einem sichtbaren Hinweis.
     *
     * @param svg liefert zu einer Kennung das SVG oder {@code null}
     */
    public static String inline(String markdown, Function<String, String> svg) {
        if (markdown == null || !markdown.contains(DocumentChatTools.LINK_SCHEME)) {
            return markdown;
        }
        Matcher match = REFERENCE.matcher(markdown);
        StringBuilder out = new StringBuilder();
        while (match.find()) {
            String content = svg.apply(match.group(2).toUpperCase(Locale.ROOT));
            String replacement = content == null || content.isBlank()
                    ? "*[Grafik " + match.group(2) + " fehlt]*"
                    : "\n\n````svg\n" + content.strip() + "\n````\n";
            match.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        match.appendTail(out);
        return out.toString();
    }

    /** Ein Codeblock mit Zaun aus {@code ```} oder {@code ~~~}; der schließende Zaun ist mindestens so lang. */
    private static final Pattern FENCE = Pattern.compile(
            "(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n(.*?)\\n {0,3}\\1[`~]*[ \\t]*$", Pattern.DOTALL);

    /**
     * Ersetzt den Codeblock, dessen Inhalt {@code code} ist (etwa ein Mermaid- oder draw.io-Diagramm), durch den
     * Verweis {@code embed}; Leerraum zählt dabei nicht. {@code null}, wenn kein Block passt.
     */
    public static String replaceBlock(String markdown, String code, String embed) {
        if (markdown == null || code == null || code.isBlank()) {
            return null;
        }
        String wanted = code.replaceAll("\\s+", "");
        Matcher match = FENCE.matcher(markdown);
        while (match.find()) {
            if (match.group(2).replaceAll("\\s+", "").equals(wanted)) {
                return markdown.substring(0, match.start()) + embed + markdown.substring(match.end());
            }
        }
        return null;
    }
}

package com.aresstack.spaceharvester.app.ui.markdown;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Macht Mermaid-Flussdiagramme aus Modellantworten <em>nur zum Rendern</em> parserkompatibel (aus askai-java8
 * {@code MermaidRenderingSourceNormalizer}).
 *
 * <p>Sprachmodelle schreiben regelmäßig Rechteck-Beschriftungen mit nicht zitierten Sonderzeichen (runde Klammern,
 * Schrägstriche), z. B. {@code A[Tierreich (Animalia)]}. Mermaids Flussdiagramm-Grammatik lehnt das ab — die
 * öffnende {@code (} gilt als Formsyntax („got 'PS'“) —, während die zitierte Form {@code A["Tierreich (Animalia)"]}
 * akzeptiert wird. Dieser Normalisierer setzt genau solche klar reparierbaren Rechteck-Beschriftungen in
 * Anführungszeichen und ändert sonst nichts.
 *
 * <p>Bewusst konservativ — er ist <strong>keine</strong> Mermaid-Grammatik. Er zitiert nur eine
 * Rechteck-Beschriftung {@code id[...]}, deren erstes Zeichen kein Formzeichen ({@code [ ( / \ "}) ist und die ein
 * zitierpflichtiges Zeichen enthält. Jedes andere Mermaid-Konstrukt — bereits zitierte Beschriftungen, Unterprogramm
 * {@code [[...]]}, Zylinder {@code [(...)]}, Kreis {@code ((...))}, Raute {@code {...}}, Sechseck {@code {{...}}},
 * asymmetrisch {@code >...]}, Parallelogramme {@code [/.../]} / {@code [\...\]}, Kommentare und andere Diagrammtypen —
 * bleibt Byte für Byte unverändert.
 *
 * <p>Die Umformung ist idempotent: {@code normalize(normalize(x)).equals(normalize(x))}.
 *
 * <p>Kein Swing, kein I/O — Zeichenkette rein, Zeichenkette raus.
 */
final class MermaidRenderingSourceNormalizer {

    /**
     * Ein Rechteck-Knoten: eine Kennung, {@code [}, dann eine Beschriftung, deren erstes Zeichen weder Formzeichen
     * noch Anführungszeichen ist, gefolgt von Text ohne weitere Klammern, geschlossen durch ein einzelnes {@code ]}.
     */
    private static final Pattern RECTANGLE_NODE =
            Pattern.compile("([A-Za-z0-9_-]+)\\[([^\\[\\]\"(/\\\\][^\\[\\]]*)\\]");

    /** Zeichen, die eine nicht zitierte Rechteck-Beschriftung ungültig machen und durch Zitieren sicher repariert werden. */
    private static final String QUOTING_TRIGGERS = "()/";

    /**
     * @param diagramCode der ursprüngliche Mermaid-Quelltext (darf {@code null} sein)
     * @return eine renderbare Kopie oder dieselbe Referenz, wenn nichts zu reparieren war
     */
    String normalize(String diagramCode) {
        if (diagramCode == null || diagramCode.trim().isEmpty()) {
            return diagramCode;
        }
        String[] lines = diagramCode.split("\n", -1);
        if (!isFlowchart(lines)) {
            return diagramCode;
        }
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("%%")) {
                continue; // Kommentare und %%{init}%%-Zeilen nie anfassen
            }
            String rewritten = quoteRectangleLabels(line);
            if (!rewritten.equals(line)) {
                lines[i] = rewritten;
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : diagramCode;
    }

    private static String quoteRectangleLabels(String line) {
        Matcher matcher = RECTANGLE_NODE.matcher(line);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String id = matcher.group(1);
            String label = matcher.group(2);
            String replacement = needsQuoting(label)
                    ? id + "[\"" + label + "\"]"
                    : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean needsQuoting(String label) {
        if (label.indexOf('"') >= 0) {
            return false; // eine Beschriftung mit Anführungszeichen lässt sich nicht sicher erneut zitieren
        }
        for (int i = 0; i < label.length(); i++) {
            if (QUOTING_TRIGGERS.indexOf(label.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Nur für {@code graph}/{@code flowchart}; andere Diagrammtypen bleiben unberührt. */
    private static boolean isFlowchart(String[] lines) {
        boolean inFrontmatter = false;
        boolean frontmatterPossible = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (inFrontmatter) {
                if (line.equals("---")) {
                    inFrontmatter = false;
                }
                continue;
            }
            if (frontmatterPossible && line.equals("---")) {
                inFrontmatter = true;
                frontmatterPossible = false;
                continue;
            }
            frontmatterPossible = false;
            if (line.startsWith("%%")) {
                continue; // Kommentare und %%{init}%%-Direktiven stehen vor dem Diagramm-Schlüsselwort
            }
            String keyword = line.toLowerCase(Locale.ROOT);
            return keyword.equals("graph") || keyword.startsWith("graph ")
                    || keyword.equals("flowchart") || keyword.startsWith("flowchart ");
        }
        return false;
    }
}

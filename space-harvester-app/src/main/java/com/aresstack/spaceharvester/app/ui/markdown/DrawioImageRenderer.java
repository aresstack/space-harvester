package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.spaceharvester.application.diagrams.Diagrams;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * draw.io-Diagramme in der Chat-Antwort (Angelo 24.11.2026): dieselbe Bibliothek wie Seiten und Exporte, drawio-java
 * auf GraalJS über {@link Diagrams#drawio}; das SVG rastert {@link SvgImageRenderer} wie jedes SVG-Bild der Antwort.
 * Was drawio-java nicht vollständig zeichnen kann, scheitert mit dem Grund aus {@link Diagrams.Rendering#problem()};
 * die Blase zeigt dann den Quelltext.
 */
final class DrawioImageRenderer implements MermaidImageRenderer {

    private static final Pattern XML_PROLOG = Pattern.compile("^\\s*<\\?xml.*?\\?>", Pattern.DOTALL);

    private final MermaidImageRenderer svg;

    DrawioImageRenderer(MermaidImageRenderer svg) {
        this.svg = svg;
    }

    @Override
    public BufferedImage render(String diagramCode, int width) {
        Diagrams.Rendering rendering = Diagrams.drawio(diagramCode.strip().getBytes(StandardCharsets.UTF_8));
        if (!rendering.drawn()) {
            throw new IllegalStateException(rendering.problem());
        }
        return svg.render(new String(rendering.content(), StandardCharsets.UTF_8), width);
    }

    /**
     * Ob ein Codeblock ein draw.io-Diagramm ist: Sprache {@code drawio}, {@code draw.io}, {@code mxgraph} oder
     * {@code mxfile}, oder ein XML-Block (auch ohne Sprache), dessen Wurzel {@code <mxfile} oder {@code <mxGraphModel}
     * ist.
     */
    static boolean isDrawioBlock(String language, String code) {
        String normalized = language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
        boolean rooted = looksLikeDrawio(code);
        return switch (normalized) {
            case "drawio", "draw.io", "mxgraph", "mxfile" -> rooted;
            case "xml", "" -> rooted;
            default -> false;
        };
    }

    private static boolean looksLikeDrawio(String code) {
        if (code == null) {
            return false;
        }
        String text = XML_PROLOG.matcher(code).replaceFirst("").strip();
        return text.startsWith("<mxfile") || text.startsWith("<mxGraphModel");
    }
}

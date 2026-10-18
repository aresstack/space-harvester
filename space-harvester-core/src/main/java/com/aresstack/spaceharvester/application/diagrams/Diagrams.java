package com.aresstack.spaceharvester.application.diagrams;

import com.aresstack.spaceharvester.document.AresStackMermaidImageRenderer;
import com.aresstack.spaceharvester.document.DrawioDiagramRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Diagramm-Rendering des Core (#190): draw.io ({@code mxfile}) wird SVG über JGraphX, Mermaid wird PNG über
 * mermaid-java, beides offline und mit denselben Renderern wie der Enterprise AI Client. Eingabe sind die Quelldaten
 * aus dem Datenbestand, nie eine Adresse; der Dienst ruft nichts ab. Die textuelle Quelle bleibt für Suche und KI
 * maßgeblich, die Grafik ist nur Darstellung. Word, PDF und Markdown nutzen ihn; die Leseansicht kann es ebenso.
 */
public final class Diagrams {

    public static final String SVG = "image/svg+xml";
    public static final String PNG = "image/png";
    private static final String DRAWIO_SUFFIX = ".drawio";
    /** Breite, in der Mermaid gezeichnet wird (wie {@code ExportGraphics}). */
    private static final int MERMAID_WIDTH = 1200;

    private Diagrams() {
    }

    /** Ergebnis: die Grafik oder der Grund, warum es keine gibt (ohne Inhalte des Diagramms). */
    public record Rendering(String mediaType, byte[] content, String problem) {

        public Rendering {
            content = content == null ? null : content.clone();
        }

        static Rendering drawn(String mediaType, byte[] content) {
            return new Rendering(mediaType, content, null);
        }

        static Rendering notDrawn(String problem) {
            return new Rendering(null, null, Objects.requireNonNull(problem));
        }

        public boolean drawn() {
            return content != null;
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    /** draw.io-Quelldaten, erkannt wie {@code AssetKind.DIAGRAM}: Medientyp {@code application/vnd.jgraph.mxfile} oder {@code *.drawio}. */
    public static boolean isDrawio(String mediaType, String filename) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return type.equals("application/vnd.jgraph.mxfile") || name.endsWith(DRAWIO_SUFFIX);
    }

    /** Der Diagrammname, wie ihn das Makro nennt: der Anhangname ohne {@code .drawio}. */
    public static String drawioName(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(DRAWIO_SUFFIX)
                ? filename.substring(0, filename.length() - DRAWIO_SUFFIX.length()) : filename;
    }

    /** Die Vorschau, die das draw.io-Plugin neben dem Diagramm ablegt: {@code <Name>.png}. */
    public static boolean isDrawioPreview(String filename, String diagramName) {
        String name = filename.toLowerCase(Locale.ROOT);
        String diagram = diagramName.toLowerCase(Locale.ROOT);
        return name.equals(diagram + ".png") || name.equals(diagram + DRAWIO_SUFFIX + ".png");
    }

    /** draw.io als SVG; nicht vollständig darstellbare Diagramme werden gemeldet statt geraten. */
    public static Rendering drawio(byte[] mxfile) {
        try {
            return Rendering.drawn(SVG, DrawioDiagramRenderer.toSvg(new String(mxfile, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (DrawioDiagramRenderer.Unsupported e) {
            return Rendering.notDrawn("draw.io nicht darstellbar: " + e.getMessage());
        } catch (RuntimeException e) {
            return Rendering.notDrawn("draw.io nicht darstellbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Mermaid als PNG. */
    public static Rendering mermaid(String source) {
        BufferedImage image;
        try {
            image = new AresStackMermaidImageRenderer().render(source, MERMAID_WIDTH);
        } catch (RuntimeException | LinkageError e) {
            return Rendering.notDrawn("Mermaid nicht darstellbar (" + e.getClass().getSimpleName() + ")");
        }
        if (image == null) {
            return Rendering.notDrawn("Mermaid nicht darstellbar");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            return Rendering.notDrawn("Mermaid nicht darstellbar (" + e.getClass().getSimpleName() + ")");
        }
        return Rendering.drawn(PNG, out.toByteArray());
    }
}

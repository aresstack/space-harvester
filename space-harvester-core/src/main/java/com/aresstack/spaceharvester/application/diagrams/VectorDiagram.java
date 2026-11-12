package com.aresstack.spaceharvester.application.diagrams;

import com.aresstack.spaceharvester.document.SvgDrawing;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein Diagramm als Vektorzeichnung für Oberflächen (Leseansicht, Bildbetrachter): Es wird bei jedem Maßstab direkt
 * gezeichnet ({@link #paint}), nie vorher in einer festen Breite gerastert. Damit bleibt etwa ein 3288 Pixel breites
 * draw.io-Diagramm bei jeder Vergrößerung so scharf wie im Browser. Quelle ist das SVG, das {@link Diagrams#drawio}
 * aus den Quelldaten zeichnet oder die Quelle selbst liefert; {@link #svg()} gibt es unverändert zum Speichern zurück.
 */
public final class VectorDiagram {

    private final byte[] svg;
    private final SvgDrawing drawing;

    private VectorDiagram(byte[] svg, SvgDrawing drawing) {
        this.svg = svg;
        this.drawing = drawing;
    }

    /** Aus SVG-Bytes (UTF-8); leer, wenn es kein darstellbares SVG ist. */
    public static Optional<VectorDiagram> of(byte[] svg) {
        Objects.requireNonNull(svg, "svg");
        try {
            return Optional.of(new VectorDiagram(svg.clone(),
                    SvgDrawing.parse(new String(svg, StandardCharsets.UTF_8))));
        } catch (SvgDrawing.Unsupported | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** Eigenbreite (Pixel bei Maßstab 1, wie im Browser bei 100 %). */
    public double width() {
        return drawing.width();
    }

    /** Eigenhöhe (Pixel bei Maßstab 1). */
    public double height() {
        return drawing.height();
    }

    /**
     * Zeichnet bei {@code scale} (1 = Eigengröße) ab dem Ursprung von {@code g}; Verschiebung und Ausschnitt von
     * {@code g} gelten, gezeichnet wird nur, was im Clip liegt. Der Zustand von {@code g} bleibt unverändert.
     */
    public void paint(Graphics2D g, double scale) {
        drawing.paint(g, scale);
    }

    /** Rasterbild in genau {@code width} Pixeln Breite (transparent), etwa für den Fließtext oder als PNG-Export. */
    public BufferedImage raster(int width) {
        return drawing.raster(width);
    }

    /** Das SVG selbst, etwa zum Speichern ohne Qualitätsverlust. */
    public byte[] svg() {
        return svg.clone();
    }
}

package com.aresstack.spaceharvester.document;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringReader;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.DocumentLoader;
import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.NoLoadExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.util.ParsedURL;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.svg.SVGDocument;

/**
 * Ein SVG als Vektorzeichnung (Batik-GVT-Baum, einmal aufgebaut): Es wird bei jedem Maßstab direkt in ein
 * {@link Graphics2D} gezeichnet statt vorher in einer festen Breite gerastert. So bleibt ein Diagramm bei jeder
 * Vergrößerung scharf, auch ein sehr breites (Leseansicht, Bildbetrachter). Eingabe wie bei {@link SvgImageRenderer}
 * gesäubert ({@link SvgImageRenderer#sanitize}); Skripte und externe Ressourcen lädt Batik nie.
 */
public final class SvgDrawing {

    private final GraphicsNode root;
    private final double width;
    private final double height;

    private SvgDrawing(GraphicsNode root, double width, double height) {
        this.root = root;
        this.width = width;
        this.height = height;
    }

    /** Kein darstellbares SVG; die Meldung nennt keine Inhalte. */
    public static final class Unsupported extends Exception {
        Unsupported(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Baut die Zeichnung aus SVG-Text.
     *
     * @throws Unsupported wenn der Text kein lesbares SVG ist oder keine Größe hat
     */
    public static SvgDrawing parse(String svgSource) throws Unsupported {
        String svg = SvgImageRenderer.sanitize(svgSource);
        if (svg.isEmpty()) {
            throw new Unsupported("kein SVG", null);
        }
        GraphicsNode node;
        Dimension2D size;
        try {
            SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName());
            SVGDocument document = factory.createSVGDocument("file:///drawing.svg", new StringReader(svg));
            OfflineAgent agent = new OfflineAgent();
            BridgeContext context = new BridgeContext(agent, new DocumentLoader(agent));
            context.setDynamicState(BridgeContext.STATIC);
            node = new GVTBuilder().build(context, document);
            size = context.getDocumentSize();
        } catch (IOException | RuntimeException e) {
            throw new Unsupported("SVG nicht lesbar (" + e.getClass().getSimpleName() + ")", e);
        }
        double w = size == null ? 0 : size.getWidth();
        double h = size == null ? 0 : size.getHeight();
        if (!(w > 0 && h > 0)) {
            Rectangle2D bounds = node == null ? null : node.getPrimitiveBounds();
            w = bounds == null ? 0 : bounds.getMaxX();
            h = bounds == null ? 0 : bounds.getMaxY();
        }
        if (node == null || !(w > 0 && h > 0)) {
            throw new Unsupported("SVG ohne Größe", null);
        }
        return new SvgDrawing(node, w, h);
    }

    /** Eigenbreite in SVG-Einheiten (Pixel bei Maßstab 1). */
    public double width() {
        return width;
    }

    /** Eigenhöhe in SVG-Einheiten (Pixel bei Maßstab 1). */
    public double height() {
        return height;
    }

    /**
     * Zeichnet bei Maßstab {@code scale} ab dem Ursprung von {@code g}; Transformation und Clip von {@code g} gelten
     * (Verschieben, Ausschnitt). Der Zustand von {@code g} bleibt danach unverändert.
     */
    public synchronized void paint(Graphics2D g, double scale) {
        Graphics2D copy = (Graphics2D) g.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            copy.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            copy.scale(scale, scale);
            root.paint(copy);
        } finally {
            copy.dispose();
        }
    }

    /**
     * Ein Rasterbild genau in {@code targetWidth} Pixeln Breite (Höhe im Seitenverhältnis), transparent, etwa für
     * die Anzeige im Fließtext oder zum Speichern als PNG in einer gewählten Größe.
     */
    public BufferedImage raster(int targetWidth) {
        int w = Math.max(1, targetWidth);
        double scale = w / width;
        int h = Math.max(1, (int) Math.ceil(height * scale));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            paint(g, scale);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Batik ohne Netz: keine externen Ressourcen, keine Skripte, keine Ausgabe auf der Konsole. */
    private static final class OfflineAgent extends UserAgentAdapter {

        @Override
        public ExternalResourceSecurity getExternalResourceSecurity(ParsedURL resourceURL, ParsedURL docURL) {
            return new NoLoadExternalResourceSecurity();
        }

        @Override
        public ScriptSecurity getScriptSecurity(String scriptType, ParsedURL scriptURL, ParsedURL docURL) {
            return new NoLoadScriptSecurity(scriptType);
        }

        @Override
        public void displayError(Exception e) {
            // Ein nicht ladbares Teilstück (etwa ein gesperrtes Bild) fehlt nur; der Rest wird gezeichnet.
        }

        @Override
        public void displayError(String message) {
            // wie oben
        }
    }
}

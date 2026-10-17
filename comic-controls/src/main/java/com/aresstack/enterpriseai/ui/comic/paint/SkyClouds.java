package com.aresstack.enterpriseai.ui.comic.paint;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.BoundedRangeModel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Die gemeinsame Mechanik der Wolkenhimmel aus askai-java8 arch {@code ResearchOutOfScopeSky}, unverändert
 * herausgelöst, damit der Himmel des Agent-Modus und der Navigationsbanner des Lesebereichs dieselbe Zeichnung nutzen:
 * Wolkensilhouette, Wolkenfläche mit Rand, Textgrundlinie, der durchsichtige senkrechte Verlauf (oben fast deckend,
 * unten ganz durchsichtig, keine harte Kante) und der Mausrad-Vorrang einer inneren Bildlaufleiste.
 */
public final class SkyClouds {

    private SkyClouds() {
    }

    /** Die weiche Wolkensilhouette: abgerundeter Körper mit leicht unregelmäßiger Oberkante, kein Emoji. */
    public static Area cloudShape(int width, int height) {
        float bodyTop = 7f;
        Area cloud = new Area(new RoundRectangle2D.Float(0.6f, bodyTop, width - 1.2f,
                height - bodyTop - 0.6f, height - bodyTop, height - bodyTop));
        if (width > 46) {
            cloud.add(new Area(new Ellipse2D.Float(width * 0.16f, 2.5f, 15f, 15f)));
            cloud.add(new Area(new Ellipse2D.Float(width * 0.42f, 0.8f, 18f, 18f)));
            cloud.add(new Area(new Ellipse2D.Float(width * 0.66f, 3.2f, 13f, 13f)));
        }
        return cloud;
    }

    /** Wolkenfläche und Rand (Hover heller), wie die Wolken des Himmels. */
    public static void paintCloudBase(Graphics2D g2, int width, int height, boolean hovered) {
        paintCloudBase(g2, width, height, hovered, null);
    }

    /**
     * Wie {@link #paintCloudBase(Graphics2D, int, int, boolean)}, mit eigenem Randstrich (z. B. gestrichelt für eine
     * erzeugte Bezeichnung); {@code null}: der Standardstrich.
     */
    public static void paintCloudBase(Graphics2D g2, int width, int height, boolean hovered, Stroke border) {
        Area shape = cloudShape(width, height);
        g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_SURFACE
                : ResearchUiPalette.CLOUD_SURFACE);
        g2.fill(shape);
        g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_BORDER
                : ResearchUiPalette.CLOUD_BORDER);
        g2.setStroke(border == null ? new BasicStroke(1.2f) : border);
        g2.draw(shape);
    }

    /** Textgrundlinie im Wolkenkörper (dem ruhigen unteren Teil der Silhouette). */
    public static int cloudTextY(FontMetrics metrics, int height) {
        float bodyTop = 7f;
        return Math.round(bodyTop + (height - bodyTop - metrics.getHeight()) / 2f)
                + metrics.getAscent();
    }

    /**
     * Der Himmel hinter den Wolken: Luft statt Fläche, oben stark deckend, in der Mitte durchscheinend, unten ganz
     * durchsichtig; dazu zwei sehr blasse Hintergrundwolken.
     *
     * @param contentBottom Unterkante der bedienbaren Zone; darunter hängt der Auslauf
     * @param skyHeight     gemalte Höhe (Zone plus Auslauf)
     */
    public static void paintSky(Graphics2D g2, int width, int contentBottom, int skyHeight) {
        if (skyHeight <= 0 || contentBottom <= 0) {
            return;
        }
        Color top = ResearchUiPalette.SKY_TOP;
        float contentStop = Math.max(0.1f, Math.min(0.85f,
                contentBottom / (float) skyHeight));
        LinearGradientPaint sky = new LinearGradientPaint(0f, 0f, 0f, skyHeight,
                new float[]{0f, contentStop * 0.55f, contentStop, 1f},
                new Color[]{
                        new Color(top.getRed(), top.getGreen(), top.getBlue(), 242),
                        new Color(top.getRed(), top.getGreen(), top.getBlue(), 205),
                        new Color(top.getRed(), top.getGreen(), top.getBlue(), 105),
                        new Color(top.getRed(), top.getGreen(), top.getBlue(), 0)});
        g2.setPaint(sky);
        g2.fillRect(0, 0, width, skyHeight);

        g2.setColor(new Color(255, 255, 255, 30));
        g2.fill(new Ellipse2D.Float(width * 0.08f, contentBottom * 0.15f, width * 0.28f,
                Math.max(18f, contentBottom * 0.4f)));
        g2.fill(new Ellipse2D.Float(width * 0.58f, contentBottom * 0.45f, width * 0.34f,
                Math.max(16f, contentBottom * 0.35f)));
    }

    /**
     * Mausrad-Vorrang: hat die innere Bildlaufleiste etwas zu rollen, rollt sie und verbraucht das Ereignis; sonst geht
     * es an den Elternbehälter von {@code owner} weiter, damit darunter gerollt wird – nie beide zugleich.
     */
    public static void installWheelPriority(final JScrollPane scroll, final Component owner) {
        scroll.setWheelScrollingEnabled(false);
        scroll.addMouseWheelListener(event -> {
            BoundedRangeModel model = scroll.getVerticalScrollBar().getModel();
            boolean scrollable = model.getExtent() > 0
                    && model.getExtent() < model.getMaximum() - model.getMinimum();
            if (scrollable) {
                int delta = (int) Math.round(event.getPreciseWheelRotation()
                        * event.getScrollAmount() * 16);
                if (delta == 0 && event.getPreciseWheelRotation() != 0) {
                    delta = event.getPreciseWheelRotation() < 0 ? -1 : 1;
                }
                model.setValue(model.getValue() + delta);
                event.consume();
            } else {
                Container parent = owner.getParent();
                if (parent != null) {
                    parent.dispatchEvent(SwingUtilities.convertMouseEvent(
                            (Component) event.getSource(), event, parent));
                }
            }
        });
    }
}

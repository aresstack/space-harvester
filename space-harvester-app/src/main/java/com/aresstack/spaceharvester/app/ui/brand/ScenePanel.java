package com.aresstack.spaceharvester.app.ui.brand;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Zeigt die Weltraumszene als abgerundete Kachel mit Tintenkontur, so groß wie der Platz erlaubt, höchstens
 * {@code maxSide}. Die schwarzen Ecken des Originals liegen außerhalb der Rundung und werden nicht gezeichnet.
 */
public final class ScenePanel extends JComponent {

    /** Rand des Originals bis zur abgerundeten Kachel, als Anteil der Kantenlänge. */
    private static final double INSET = 0.02;
    /** Radius der Rundung als Anteil der Kantenlänge, etwas größer als im Original, damit keine Ecke durchscheint. */
    private static final double RADIUS = 0.15;

    private final int maxSide;
    private final Color outline;
    private BufferedImage scaled;

    public ScenePanel(int preferredSide, int maxSide, Color outline) {
        this.maxSide = maxSide;
        this.outline = outline;
        setOpaque(false);
        setPreferredSize(new Dimension(preferredSide, preferredSide));
        setMinimumSize(new Dimension(preferredSide / 2, preferredSide / 2));
        setToolTipText("Space Harvester");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        int side = Math.min(maxSide, Math.min(getWidth(), getHeight())) - 2;
        if (side < 16) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        // In Gerätepixeln verkleinern, damit die Szene bei 150 % oder 200 % Skalierung scharf bleibt.
        int pixels = (int) Math.round(side * g.getTransform().getScaleX());
        BufferedImage scene = BrandImages.scene();
        int inset = (int) Math.round(scene.getWidth() * INSET);
        if (scaled == null || scaled.getWidth() != pixels) {
            scaled = BrandImages.scale(scene.getSubimage(inset, inset, scene.getWidth() - 2 * inset,
                    scene.getHeight() - 2 * inset), pixels, pixels);
        }
        int x = (getWidth() - side) / 2;
        int y = (getHeight() - side) / 2;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double arc = side * RADIUS * 2;
        Shape tile = new RoundRectangle2D.Double(x, y, side, side, arc, arc);
        g.setClip(tile);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(scaled, x, y, side, side, null);
        g.setClip(null);
        g.setColor(outline);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, side - 1, side - 1, arc, arc));
        g.dispose();
    }
}

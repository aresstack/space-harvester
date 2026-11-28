package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Die Weihnachtsmütze (Angelo 28.11.2026): rot mit weißem Rand und weißer Bommel, leicht schräg. Sie sitzt in der
 * Weihnachtszeit auf dem Anfangsbuchstaben des letzten Worts im Titel, etwa dem „H“ von „Harvester“ ({@link PerspectiveSwitcher}); gezeichnet und in jeder
 * Skalierung scharf.
 */
public final class SantaHat {

    /** Breite der Mütze in Pixeln, passend zu einem Großbuchstaben des Titels. */
    public static final int WIDTH = 11;
    /** Höhe der Mütze im Verhältnis zu ihrer Breite. */
    static final double HEIGHT_SHARE = 0.8;
    /** Neigung der Mütze nach links, in Grad. */
    static final double TILT_DEGREES = -18;
    static final Color RED = Comic.RED;
    static final Color FUR = Color.WHITE;

    private SantaHat() {
    }

    /** Die Höhe der Mütze. */
    public static double height() {
        return WIDTH * HEIGHT_SHARE;
    }

    /**
     * Zeichnet die Mütze so, dass ihr Rand mit der linken Kante bei {@code left} und der Unterkante bei
     * {@code bottom} sitzt.
     */
    public static void paint(Graphics graphics, double left, double bottom) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double w = WIDTH;
            double h = height();
            g.translate(left, bottom - h);
            g.rotate(Math.toRadians(TILT_DEGREES), w / 2, h);
            float line = (float) Math.max(0.8, w / 16);
            // Der rote Kegel, die Spitze fällt nach rechts.
            Path2D cone = new Path2D.Double();
            cone.moveTo(w * 0.08, h * 0.78);
            cone.curveTo(w * 0.12, h * 0.2, w * 0.55, -h * 0.05, w * 0.98, h * 0.42);
            cone.curveTo(w * 0.8, h * 0.32, w * 0.86, h * 0.6, w * 0.92, h * 0.78);
            cone.closePath();
            g.setColor(RED);
            g.fill(cone);
            g.setColor(Comic.INK);
            g.setStroke(new BasicStroke(line, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(cone);
            // Der weiße Rand.
            RoundRectangle2D brim = new RoundRectangle2D.Double(0, h * 0.72, w, h * 0.28, h * 0.28, h * 0.28);
            g.setColor(FUR);
            g.fill(brim);
            g.setColor(Comic.INK);
            g.draw(brim);
            // Die Bommel an der Spitze.
            double ball = h * 0.3;
            Ellipse2D pompom = new Ellipse2D.Double(w * 0.98 - ball / 2, h * 0.42 - ball / 2, ball, ball);
            g.setColor(FUR);
            g.fill(pompom);
            g.setColor(Comic.INK);
            g.draw(pompom);
        } finally {
            g.dispose();
        }
    }
}

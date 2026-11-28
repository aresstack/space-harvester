package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

/**
 * Ein Symbol mit Weihnachtsmütze (Angelo 28.11.2026): In der Weihnachtszeit ({@link Season#christmasNow()}) sitzt
 * oben links schräg eine rote Mütze mit weißem Rand und weißer Bommel auf dem Symbol, sonst zeigt es sich
 * unverändert. Die Größe bleibt die des Symbols; die Mütze ist gezeichnet und in jeder Skalierung scharf.
 */
public final class SantaHat implements Icon {

    /** Breite der Mütze im Verhältnis zur Breite des Symbols. */
    static final double WIDTH_SHARE = 0.46;
    /** Neigung der Mütze nach links, in Grad. */
    static final double TILT_DEGREES = -18;
    static final Color RED = Comic.RED;
    static final Color FUR = Color.WHITE;

    private final Icon base;

    public SantaHat(Icon base) {
        this.base = base;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        base.paintIcon(component, graphics, x, y);
        if (!Season.christmasNow()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double w = getIconWidth() * WIDTH_SHARE;
            double h = w * 0.8;
            g.translate(x, y + h * 0.1);
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

    @Override
    public int getIconWidth() {
        return base.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return base.getIconHeight();
    }
}

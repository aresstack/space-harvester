package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Kleine Vektorsymbole für Icon-Knöpfe (16 × 16, skaliert mit der Bildschirmauflösung): Aktualisieren, Stift,
 * Papierkorb, Schlüssel, Mehr, Aufklappen (Pfeil rechts), Zuklappen (Pfeil unten),
 * Übernehmen (Haken), Verwerfen (Kreuz). Gemalt in der Vordergrundfarbe der Komponente, damit Hover und deaktiviert stimmen.
 */
enum Glyph implements Icon {

    REFRESH, PENCIL, TRASH, KEY, MORE, EXPAND, COLLAPSE, ACCEPT, CANCEL;

    private static final int SIZE = 16;

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            Color color = c == null ? Comic.INK : c.isEnabled() ? c.getForeground() : Comic.LINE;
            g.setColor(color);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (this) {
                case REFRESH -> {
                    g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 70, 280, Arc2D.OPEN));
                    Path2D head = new Path2D.Double();
                    head.moveTo(9.2, 0.8);
                    head.lineTo(12.4, 3.2);
                    head.lineTo(8.8, 5.2);
                    g.draw(head);
                }
                case PENCIL -> {
                    Path2D body = new Path2D.Double();
                    body.moveTo(3, 13);
                    body.lineTo(3.6, 10);
                    body.lineTo(11, 2.6);
                    body.lineTo(13.4, 5);
                    body.lineTo(6, 12.4);
                    body.closePath();
                    g.draw(body);
                    g.draw(new Line2D.Double(9.4, 4.2, 11.8, 6.6));
                }
                case TRASH -> {
                    g.draw(new Line2D.Double(2.5, 4, 13.5, 4));
                    g.draw(new Line2D.Double(6.2, 4, 6.8, 2.2));
                    g.draw(new Line2D.Double(9.8, 4, 9.2, 2.2));
                    g.draw(new Line2D.Double(6.8, 2.2, 9.2, 2.2));
                    g.draw(new RoundRectangle2D.Double(4, 4, 8, 10, 2, 2));
                    g.draw(new Line2D.Double(6.6, 6.8, 6.6, 11.4));
                    g.draw(new Line2D.Double(9.4, 6.8, 9.4, 11.4));
                }
                case KEY -> {
                    g.draw(new Ellipse2D.Double(1.8, 5, 6, 6));
                    g.draw(new Line2D.Double(7.8, 8, 14, 8));
                    g.draw(new Line2D.Double(11.5, 8, 11.5, 10.6));
                    g.draw(new Line2D.Double(13.6, 8, 13.6, 10));
                }
                case EXPAND -> {
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(6, 4);
                    arrow.lineTo(10.5, 8);
                    arrow.lineTo(6, 12);
                    g.draw(arrow);
                }
                case COLLAPSE -> {
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(4, 6);
                    arrow.lineTo(8, 10.5);
                    arrow.lineTo(12, 6);
                    g.draw(arrow);
                }
                case ACCEPT -> {
                    Path2D tick = new Path2D.Double();
                    tick.moveTo(3, 8.5);
                    tick.lineTo(6.5, 12);
                    tick.lineTo(13, 4.5);
                    g.draw(tick);
                }
                case CANCEL -> {
                    g.draw(new Line2D.Double(4, 4, 12, 12));
                    g.draw(new Line2D.Double(12, 4, 4, 12));
                }
                case MORE -> {
                    for (double cx : new double[] {3, 8, 13}) {
                        g.fill(new Ellipse2D.Double(cx - 1.4, 6.6, 2.8, 2.8));
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}

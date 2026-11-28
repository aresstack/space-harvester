package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Pfeile im Kreis für den Sprung zurück (gegen den Uhrzeigersinn) und vor (im Uhrzeigersinn), Angelo 20.11.2026; in
 * der Farbe der übrigen Knöpfe des Players.
 */
final class VideoSkipIcon implements Icon {

    private static final int SIZE = 17;
    private final boolean forward;

    VideoSkipIcon(boolean forward) {
        this.forward = forward;
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D paint = (Graphics2D) graphics.create();
        try {
            paint.translate(x, y);
            if (!forward) {
                // Gespiegelt: derselbe Kreis, gegen den Uhrzeigersinn.
                paint.translate(SIZE, 0);
                paint.scale(-1, 1);
            }
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paint.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            paint.setColor(component.isEnabled() ? AppColors.of("app.VideoSkipIcon.glyph") : AppColors.of("app.VideoSkipIcon.disabledGlyph"));
            // Ein offener Kreis im Uhrzeigersinn, oben eine Lücke; die Spitze am Ende zeigt in die Lücke hinein.
            paint.draw(new Arc2D.Double(2.5, 3.5, 12, 12, 70, -310, Arc2D.OPEN));
            paint.draw(new Line2D.Double(5.5, 4.3, 2.1, 3.7));
            paint.draw(new Line2D.Double(5.5, 4.3, 4.3, 7.6));
        } finally {
            paint.dispose();
        }
    }
}

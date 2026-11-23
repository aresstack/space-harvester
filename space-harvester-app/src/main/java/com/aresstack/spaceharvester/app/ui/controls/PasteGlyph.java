package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

/**
 * Einfügen: ein Klemmbrett mit Klammer oben, im selben Strich wie das Kopieren-Symbol ({@link CopyButton#glyph()}),
 * in der Vordergrundfarbe des Knopfs, blass, wenn er gesperrt ist.
 */
public final class PasteGlyph implements Icon {

    private static final int SIZE = 16;

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(c == null ? Comic.INK : c.isEnabled() ? c.getForeground() : Comic.LINE);
            // Brett ohne die obere Mitte, dort sitzt die Klammer.
            Path2D board = new Path2D.Double();
            board.moveTo(5.5, 3.5);
            board.lineTo(3.5, 3.5);
            board.lineTo(3.5, 14.5);
            board.lineTo(12.5, 14.5);
            board.lineTo(12.5, 3.5);
            board.lineTo(10.5, 3.5);
            g.draw(board);
            g.draw(new RoundRectangle2D.Double(5.5, 1.8, 5, 3, 1.5, 1.5));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Line2D.Double(6, 8.5, 10, 8.5));
            g.draw(new java.awt.geom.Line2D.Double(6, 11, 9, 11));
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

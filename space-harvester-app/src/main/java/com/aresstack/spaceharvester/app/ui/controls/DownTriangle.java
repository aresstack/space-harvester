package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * Gefülltes Dreieck nach unten für einen Menüknopf neben einer Aktion (Angelo, 16.11.2026: rechts neben dem
 * Favoritenstern der Leseansicht; 17.11.2026: rechts neben dem Besen der Aufträge), in der Vordergrundfarbe des
 * Knopfs, blass, wenn er gesperrt ist.
 */
public final class DownTriangle implements Icon {

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(c.isEnabled() ? c.getForeground() : Comic.MUTED);
        g2.fillPolygon(new int[] {x + 3, x + 13, x + 8}, new int[] {y + 5, y + 5, y + 11}, 3);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return 16;
    }

    @Override
    public int getIconHeight() {
        return 16;
    }
}

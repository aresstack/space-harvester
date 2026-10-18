package com.aresstack.spaceharvester.app.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Der einheitliche Stil der Symbolaktionen: schlichtes Symbol, beim Darüberfahren ein heller Kreis mit Tintenrand
 * (Knöpfe an den Quellenkarten, Download je Space, Aktionen im Explorer und in der Auftragsliste).
 */
final class RoundHover {

    static final int SIZE = 26;
    private static final Color FILL = new Color(255, 255, 255, 170);

    private RoundHover() {
    }

    /** Zeichnet den Kreis zentriert in die Fläche {@code x, y, width, height}. */
    static void paint(Graphics graphics, int x, int y, int width, int height, boolean pressed) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(SIZE, Math.min(width, height) - 1);
            int left = x + (width - size) / 2;
            int top = y + (height - size) / 2;
            g.setColor(pressed ? Comic.LINE : FILL);
            g.fillOval(left, top, size - 1, size - 1);
            g.setColor(Comic.INK);
            g.drawOval(left, top, size - 1, size - 1);
        } finally {
            g.dispose();
        }
    }
}

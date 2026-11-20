package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Eine Trennlinie ({@code ---}) im Comic-Stil: eine ruhige, abgerundete Linie in der Trennfarbe der Blase. Ersetzt
 * {@code JSeparator}, dessen Look-and-Feel eine Doppellinie mit heller Schattenkante zeichnet, die auf farbigen Blasen
 * als weißer Strich erscheint.
 */
final class MarkdownRule extends JComponent {

    private static final int HEIGHT = 9;
    private static final int THICKNESS = 2;

    MarkdownRule(Color color) {
        setForeground(color);
        setOpaque(false);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getForeground());
            int y = (getHeight() - THICKNESS) / 2;
            g2.fillRoundRect(0, y, getWidth(), THICKNESS, THICKNESS, THICKNESS);
        } finally {
            g2.dispose();
        }
    }
}

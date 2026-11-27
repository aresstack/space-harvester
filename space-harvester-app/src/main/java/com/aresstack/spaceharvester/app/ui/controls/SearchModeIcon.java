package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Die Lupe links im Suchfeld als Umschalter zwischen den beiden Suchen (Angelo 27.11.2026): die gewohnte Lupe für die
 * Seitensuche, für die Wortsuche dieselbe Lupe vor einem kleinen „w“, das sie untersucht. Ein Klick wechselt den Modus;
 * flach wie die übrigen Knöpfe im Feld ({@link InputFrame#flatButton}), mit Tastatur erreichbar.
 */
public final class SearchModeIcon extends JButton {

    private static final Color ICON = new Color(0x666666);
    /** Breite mit „w“: Lupe und Buchstabe nebeneinander, das Feld rückt dafür etwas nach rechts. */
    private static final Dimension WORD_SIZE = new Dimension(34, 20);
    /** Breite wie die bisherige Lupe ({@link InputFrame#magnifier()}). */
    private static final Dimension PAGE_SIZE = new Dimension(24, 20);
    /** Halbmesser des Glases in Pixeln, wie bei {@link InputFrame#magnifier()}. */
    private static final int RADIUS = 5;

    private boolean word;

    public SearchModeIcon() {
        setBorderPainted(false);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName("Suchmodus");
    }

    /** Ob die Wortsuche gezeigt wird. */
    public boolean word() {
        return word;
    }

    /** Zeigt die Lupe mit „w“ (Wortsuche) bzw. die einfache Lupe (Seitensuche). */
    public void word(boolean on) {
        if (word != on) {
            word = on;
            revalidate();
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(word ? WORD_SIZE : PAGE_SIZE);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(getModel().isRollover() || hasFocus() ? ICON.darker() : ICON);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = word ? RADIUS + 5 : getWidth() / 2 - 1;
        int cy = getHeight() / 2 - 1;
        if (word) {
            // Das kleine „w“ rechts, die Lupe davor: der Griff zeigt zum Buchstaben.
            Font font = getFont().deriveFont(Font.BOLD, getFont().getSize2D());
            g2.setFont(font);
            FontMetrics metrics = g2.getFontMetrics();
            int x = cx + RADIUS + 3;
            // Mittig zur Lupe: die x-Höhe des „w“ um die Mitte des Glases.
            int y = cy + (metrics.getAscent() - metrics.getDescent()) / 2 + 1;
            g2.drawString("w", x, y);
        }
        g2.drawOval(cx - RADIUS, cy - RADIUS, 2 * RADIUS, 2 * RADIUS);
        int hx = cx + (int) (RADIUS * 0.7);
        int hy = cy + (int) (RADIUS * 0.7);
        g2.drawLine(hx, hy, hx + 4, hy + 4);
        g2.dispose();
    }
}

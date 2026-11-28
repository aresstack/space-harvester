package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Lupe links im Suchfeld als Umschalter zwischen den beiden Suchen (Angelo 27.11.2026): die gewohnte Lupe für die
 * Seitensuche, für die Wortsuche dieselbe Lupe vor einem „W“, das sie untersucht. Das „W“ liegt hinter der Lupe im
 * oberen Teil des Glases, nach hinten gekippt wie auf einem Blatt Papier, und scheint durch das Glas (Angelo 28.11.2026); beide
 * Symbole sind gleich groß. Ein Klick wechselt den Modus; flach wie die übrigen Knöpfe im Feld ({@link InputFrame#flatButton}), mit Tastatur erreichbar.
 */
public final class SearchModeIcon extends JButton {

    private static final Color ICON = AppColors.of("app.SearchModeIcon.icon");
    /** Größe beider Symbole, wie die bisherige Lupe ({@link InputFrame#magnifier()}). */
    private static final Dimension SIZE = new Dimension(24, 20);
    /** Das „W“ hinter dem Glas: eine Spur blasser und dünner als die Lupe davor. */
    private static final Color LETTER = AppColors.of("app.SearchModeIcon.letter");
    private static final float LETTER_STROKE = 1.15f;
    /** Breite des „W“ an seiner Unterkante und seine Höhe, in Pixeln. */
    private static final float LETTER_WIDTH = 6.4f;
    private static final float LETTER_HEIGHT = 4.6f;
    /** Wie weit das „W“ über der Mitte des Glases liegt: der Blick geht von unten durch die Lupe. */
    static final float LETTER_RISE = 0.9f;
    /** Nach hinten gekippt: die Oberkante ist um diesen Faktor schmaler als die Unterkante. */
    private static final float LETTER_TAPER = 0.78f;
    /** Wie hoch die mittlere Spitze des „W“ reicht, als Anteil seiner Höhe von oben. */
    private static final float LETTER_PEAK = 0.3f;
    /** Halbmesser des Glases in Pixeln, wie bei {@link InputFrame#magnifier()}. */
    static final int RADIUS = 5;

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
        return new Dimension(SIZE);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(getModel().isRollover() || hasFocus() ? ICON.darker() : ICON);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int cx = getWidth() / 2 - 1;
        int cy = getHeight() / 2 - 1;
        Color ink = g2.getColor();
        if (word) {
            // Zuerst das „W“, dann die Lupe darüber: so liegt es dahinter und scheint durch das Glas.
            g2.setColor(getModel().isRollover() || hasFocus() ? ink : LETTER);
            g2.setStroke(new BasicStroke(LETTER_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(letter(cx, cy - LETTER_RISE));
            g2.setColor(ink);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }
        g2.drawOval(cx - RADIUS, cy - RADIUS, 2 * RADIUS, 2 * RADIUS);
        int hx = cx + (int) (RADIUS * 0.7);
        int hy = cy + (int) (RADIUS * 0.7);
        g2.drawLine(hx, hy, hx + 4, hy + 4);
        g2.dispose();
    }

    /**
     * Das gekippte „W“ um die Mitte {@code (cx, cy)}: fünf Punkte, nach oben hin schmaler, als läge der Buchstabe auf
     * einem Blatt, das vom Betrachter weg zeigt.
     */
    static java.awt.geom.Path2D letter(float cx, float cy) {
        float top = cy - LETTER_HEIGHT / 2;
        float[][] points = {{-0.5f, 0f}, {-0.25f, 1f}, {0f, LETTER_PEAK}, {0.25f, 1f}, {0.5f, 0f}};
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        for (int i = 0; i < points.length; i++) {
            float depth = points[i][1];
            float width = LETTER_WIDTH * (LETTER_TAPER + (1 - LETTER_TAPER) * depth);
            float x = cx + points[i][0] * width;
            float y = top + depth * LETTER_HEIGHT;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        return path;
    }
}

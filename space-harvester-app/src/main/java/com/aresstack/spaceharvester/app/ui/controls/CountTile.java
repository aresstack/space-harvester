package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Eine Kennzahl als großer quadratischer Umschaltknopf (Angelo 24.11.2026): oben groß die Zahl, darunter klein ihre
 * Bezeichnung. Mehrere Kacheln in einer {@link javax.swing.ButtonGroup} wählen, welcher Abschnitt darunter zu sehen ist;
 * die gewählte ist dunkel gefüllt wie eine gewählte Pille. Der Text des Knopfs ist die Zahl.
 */
public final class CountTile extends JToggleButton {

    /** Kantenlänge der Kachel; groß genug für eine vierstellige Zahl und die längste Bezeichnung („Beziehungen“). */
    public static final int SIZE = 96;
    private static final int ARC = 12;
    private static final int GAP = 2;
    private static final float NUMBER_SIZE = 22f;
    private static final float CAPTION_SIZE = 12f;

    /** Dauer eines Atemzugs der zählenden Kachel. */
    private static final int BREATH_MILLIS = 3200;

    private final String caption;
    /** Nur Animation, fragt nichts ab; läuft, solange die Kachel zählt und im Fenster hängt. */
    private final javax.swing.Timer breath = new javax.swing.Timer(50, e -> repaint());
    private boolean counting;
    private long breathStart;

    public CountTile(String number, String caption) {
        super(number);
        this.caption = caption;
        setFont(Comic.semiBold(NUMBER_SIZE));
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        Cursors.set(this, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(caption);
    }

    public String caption() {
        return caption;
    }

    /**
     * Ob die Zahl noch hochzählt (Angelo 24.11.2026): Solange „atmet“ der Hintergrund der Kachel, also ein sanftes
     * Pulsieren im Atemrhythmus.
     */
    public void counting(boolean on) {
        if (counting == on) {
            return;
        }
        counting = on;
        breathStart = System.currentTimeMillis();
        if (on && isDisplayable()) {
            breath.start();
        } else {
            breath.stop();
        }
        repaint();
    }

    public boolean counting() {
        return counting;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (counting) {
            breath.start();
        }
    }

    @Override
    public void removeNotify() {
        breath.stop();
        super.removeNotify();
    }

    /** Deckkraft des Atems jetzt, zwischen 0 und 1. */
    private double breathNow() {
        double phase = ((System.currentTimeMillis() - breathStart) % BREATH_MILLIS) / (double) BREATH_MILLIS;
        return (1 - Math.cos(2 * Math.PI * phase)) / 2;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE, SIZE);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            boolean on = isSelected();
            if (on) {
                g.setColor(Comic.INK);
                g.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC);
            } else if (getModel().isRollover() && isEnabled()) {
                g.setColor(new Color(Comic.INK.getRed(), Comic.INK.getGreen(), Comic.INK.getBlue(), 20));
                g.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC);
            }
            if (counting) {
                Color tone = on ? AppColors.of("bubble.userForeground") : Comic.INK;
                int alpha = (int) Math.round((on ? 70 : 38) * breathNow());
                g.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), alpha));
                g.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC);
            }
            g.setColor(on ? Comic.INK : Comic.LINE);
            g.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC);
            if (isFocusOwner()) {
                g.setColor(on ? AppColors.of("bubble.userForeground") : Comic.INK);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
                        new float[] {2f, 2f}, 0f));
                g.drawRoundRect(4, 4, width - 9, height - 9, ARC - 4, ARC - 4);
                g.setStroke(new BasicStroke(1f));
            }
            Font big = getFont();
            Font small = Comic.regular(CAPTION_SIZE);
            FontMetrics bigMetrics = g.getFontMetrics(big);
            FontMetrics smallMetrics = g.getFontMetrics(small);
            int block = bigMetrics.getHeight() + GAP + smallMetrics.getHeight();
            int top = (height - block) / 2;
            g.setFont(big);
            g.setColor(on ? AppColors.of("bubble.userForeground") : Comic.INK);
            g.drawString(getText(), (width - bigMetrics.stringWidth(getText())) / 2, top + bigMetrics.getAscent());
            g.setFont(small);
            g.setColor(on ? AppColors.of("bubble.userForeground") : Comic.MUTED);
            g.drawString(caption, (width - smallMetrics.stringWidth(caption)) / 2,
                    top + bigMetrics.getHeight() + GAP + smallMetrics.getAscent());
        } finally {
            g.dispose();
        }
    }
}

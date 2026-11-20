package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ResearchPillButton;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Eine Pille, die die Suchleiste ({@link ReaderFind}) als Treffer markieren kann. Die Markierung wird
 * über die fertig gemalte Pille gelegt, damit die gemeinsame Bibliothek unverändert bleibt.
 */
public final class FindablePill extends ResearchPillButton {

    private final int radius;
    private Color mark;

    public FindablePill(String text, int fixedHeight, int radius, int paddingH) {
        super(text, fixedHeight, radius, paddingH);
        this.radius = radius;
    }

    /** Hebt die Pille als Suchtreffer hervor ({@code null} nimmt die Markierung weg). */
    public void setMark(Color mark) {
        this.mark = mark;
        repaint();
    }

    public Color getMark() {
        return mark;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (mark == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.SrcOver.derive(0.7f));
            g2.setColor(mark);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(mark.darker());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        } finally {
            g2.dispose();
        }
    }
}

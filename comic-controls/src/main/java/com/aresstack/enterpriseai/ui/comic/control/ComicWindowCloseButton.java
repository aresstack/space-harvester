package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

/**
 * The stylish comic ✕ (AskAI's {@code ComicOverlayPanel.CloseButton}, extracted so overlays AND
 * frameless windows share ONE painter): a round ink-outlined chip, white at rest, RED (critical)
 * on hover or while pressed, the ✕ drawn in ink (white on the red chip). Focus-free, hand cursor,
 * 24×24 by default.
 */
public class ComicWindowCloseButton extends JButton {

    private static final int DEFAULT_SIZE = 24;

    private final ComicPalette palette;
    private final int size;

    public ComicWindowCloseButton(ComicPalette palette, Runnable closeAction) {
        this(palette, closeAction, "Close", DEFAULT_SIZE);
    }

    public ComicWindowCloseButton(ComicPalette palette, final Runnable closeAction, String tooltip,
                                  int size) {
        if (palette == null || closeAction == null) {
            throw new IllegalArgumentException("palette and closeAction must not be null");
        }
        this.palette = palette;
        this.size = Math.max(16, size);
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        setFocusable(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRolloverEnabled(true);
        addActionListener(e -> closeAction.run());
    }

    /** The palette the chip paints with (for subclasses that vary the glyph). */
    protected ComicPalette palette() {
        return palette;
    }

    /** Whether the chip currently paints red (hovered or pressed). */
    public boolean isHot() {
        return getModel().isRollover() || getModel().isPressed();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(size, size);
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
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Ellipse2D chip = new Ellipse2D.Float(2f, 2f, getWidth() - 5f, getHeight() - 5f);
            boolean hot = isHot();
            g2.setColor(hot ? palette.getAccentRed() : Color.WHITE);
            g2.fill(chip);
            g2.setColor(palette.getInk());
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(chip);
            // the ✕ strokes
            g2.setColor(hot ? Color.WHITE : palette.getInk());
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int arm = Math.max(3, getWidth() / 6);
            g2.drawLine(cx - arm, cy - arm, cx + arm, cy + arm);
            g2.drawLine(cx + arm, cy - arm, cx - arm, cy + arm);
        } finally {
            g2.dispose();
        }
    }
}

package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A comic "plate" section: rounded surface with a clear ink contour, an optional colored left
 * ACCENT STRIPE (the calm way to say "done/active/critical" without a loud fill) and an optional
 * full plate fill for the moments that SHOULD pop (e.g. the currently active state). Content is
 * ordinary Swing children; the panel only paints the plate underneath them.
 */
public class ComicSectionPanel extends JPanel {

    private static final int ARC = 10;
    private static final float OUTLINE_WIDTH = 1.6f;
    private static final int STRIPE_WIDTH = 5;

    private final ComicPalette palette;
    private Color plateFill = Color.WHITE;
    private Color accentStripe;

    public ComicSectionPanel() {
        this(ComicPalette.defaultPalette());
    }

    public ComicSectionPanel(ComicPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 10));
    }

    /** The plate's fill — white by default (calm); accent colors only for states that should pop. */
    public void setPlateFill(Color fill) {
        this.plateFill = fill == null ? Color.WHITE : fill;
        repaint();
    }

    public Color getPlateFill() {
        return plateFill;
    }

    /** The left accent stripe color, or {@code null} for none. */
    public void setAccentStripe(Color stripe) {
        this.accentStripe = stripe;
        repaint();
    }

    public Color getAccentStripe() {
        return accentStripe;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D plate = new RoundRectangle2D.Float(
                    1f, 1f, getWidth() - 2f, getHeight() - 2f, ARC, ARC);
            g2.setColor(plateFill);
            g2.fill(plate);
            if (accentStripe != null) {
                g2.setColor(accentStripe);
                g2.fillRoundRect(5, 6, STRIPE_WIDTH, Math.max(4, getHeight() - 12), 4, 4);
            }
            g2.setColor(palette.getInk());
            g2.setStroke(new BasicStroke(OUTLINE_WIDTH));
            g2.draw(plate);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}

package com.aresstack.enterpriseai.ui.comic.border;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.border.Border;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * The comic contour: a clear dark ink line, optionally with rounded corners for components that
 * paint their own rounded surface. Popups are heavyweight rectangles, so {@link #popupBorder}
 * stays square; panels/buttons use the rounded variant.
 */
public final class ComicBorder implements Border {

    private final ComicPalette palette;
    private final int thickness;
    private final int arc;
    private final Insets padding;

    private ComicBorder(ComicPalette palette, int thickness, int arc, Insets padding) {
        this.palette = palette;
        this.thickness = thickness;
        this.arc = arc;
        this.padding = padding;
    }

    /** Square 2px ink contour with a little vertical padding — for dropdowns/context popups. */
    public static ComicBorder popupBorder(ComicPalette palette) {
        requirePalette(palette);
        return new ComicBorder(palette, 2, 0, new Insets(4, 2, 4, 2));
    }

    /**
     * Square 2px ink contour with a uniform grip padding — the edge of a FRAMELESS window, where
     * the padding doubles as the resize zone ({@code ComicWindowResizer}) and rounded corners would
     * leave the window's own rectangle visible.
     */
    public static ComicBorder windowBorder(ComicPalette palette, int padding) {
        requirePalette(palette);
        return new ComicBorder(palette, 2, 0, new Insets(padding, padding, padding, padding));
    }

    /**
     * The same contour and grip padding as {@link #windowBorder}, with rounded corners of {@code radius}
     * pixels — for a frameless window whose own shape is rounded with the same radius
     * ({@code ComicWindowShape}), so no square window corner shows behind the curve.
     */
    public static ComicBorder roundedWindowBorder(ComicPalette palette, int padding, int radius) {
        requirePalette(palette);
        int thickness = 2; // die Mittellinie liegt eine halbe Strichbreite innen: ihr Bogen ist um so viel kleiner,
        // damit die Außenkante der Tinte genau auf der Fensterform liegt
        return new ComicBorder(palette, thickness, Math.max(0, radius * 2 - thickness),
                new Insets(padding, padding, padding, padding));
    }

    /** Rounded ink contour for panels that want the comic outline without an impact burst. */
    public static ComicBorder roundedBorder(ComicPalette palette, int padding) {
        requirePalette(palette);
        return new ComicBorder(palette, 2, 10,
                new Insets(padding, padding, padding, padding));
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(palette.getInk());
            g2.setStroke(new BasicStroke(thickness));
            int inset = thickness / 2;
            if (arc > 0) {
                g2.drawRoundRect(x + inset, y + inset,
                        width - thickness, height - thickness, arc, arc);
            } else {
                g2.drawRect(x + inset, y + inset, width - thickness, height - thickness);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(thickness + padding.top, thickness + padding.left,
                thickness + padding.bottom, thickness + padding.right);
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    private static void requirePalette(ComicPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
    }
}

package com.aresstack.enterpriseai.ui.comic.paint;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * An icon with a small count in the upper right corner (the paperclip with the number of attachments in the reader
 * toolbar): the glyph as given, the count as a filled amber plate with an ink outline and white digits, comic style.
 * The icon grows by the plate so the count never covers the glyph's centre. Own addition.
 */
public final class CountBadgeIcon implements Icon {

    private static final int PLATE_HEIGHT = 10;

    private final Icon glyph;
    private final int count;
    private final Color plate;
    private final Color ink;

    public CountBadgeIcon(Icon glyph, int count, Color plate, Color ink) {
        if (glyph == null || plate == null || ink == null) {
            throw new IllegalArgumentException("glyph, plate and ink must not be null");
        }
        this.glyph = glyph;
        this.count = Math.max(0, count);
        this.plate = plate;
        this.ink = ink;
    }

    public int count() {
        return count;
    }

    /** The text on the plate: the count, from 100 on "99+". */
    public String label() {
        return count > 99 ? "99+" : String.valueOf(count);
    }

    private int plateWidth() {
        return Math.max(PLATE_HEIGHT, 4 + 5 * label().length());
    }

    @Override
    public int getIconWidth() {
        return glyph.getIconWidth() + plateWidth() / 2 + 1;
    }

    @Override
    public int getIconHeight() {
        return glyph.getIconHeight() + PLATE_HEIGHT / 3;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        glyph.paintIcon(c, g, x, y + PLATE_HEIGHT / 3);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = plateWidth();
            int px = x + getIconWidth() - w;
            g2.setColor(plate);
            g2.fillRoundRect(px, y, w - 1, PLATE_HEIGHT - 1, PLATE_HEIGHT, PLATE_HEIGHT);
            g2.setColor(ink);
            g2.drawRoundRect(px, y, w - 1, PLATE_HEIGHT - 1, PLATE_HEIGHT, PLATE_HEIGHT);
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 8));
            FontMetrics metrics = g2.getFontMetrics();
            String text = label();
            g2.setColor(Color.WHITE);
            g2.drawString(text, px + (w - metrics.stringWidth(text)) / 2,
                    y + (PLATE_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally {
            g2.dispose();
        }
    }
}

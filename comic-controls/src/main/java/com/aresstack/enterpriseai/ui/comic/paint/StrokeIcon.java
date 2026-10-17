package com.aresstack.enterpriseai.ui.comic.paint;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Base of AskAI's composer glyphs: a small Java2D line icon (15×15 by default, 1.7px round
 * strokes) that paints with the OWNING component's current foreground, so a button's hover or
 * emphasized foreground colors the glyph without any icon state of its own. Subclasses only draw
 * in the translated, antialiased, pre-stroked {@link Graphics2D}.
 */
public abstract class StrokeIcon implements Icon {

    private final int width;
    private final int height;

    protected StrokeIcon() {
        this(15, 15);
    }

    protected StrokeIcon(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }

    @Override
    public final void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(component.getForeground());
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            paint(g2);
        } finally {
            g2.dispose();
        }
    }

    /** Draw the glyph into a 0-based, antialiased, pre-stroked graphics. */
    protected abstract void paint(Graphics2D g2);
}

package com.aresstack.enterpriseai.ui.comic.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Shared Java2D painting for the dark research-UI controls: one place that turns on antialiasing
 * and draws the study's rounded surfaces, so no control ships its own slightly-different painter.
 */
public final class ResearchUiPainter {

    private ResearchUiPainter() {
    }

    /** A copy of {@code graphics} with the study's mandatory AA hints; caller must dispose it. */
    public static Graphics2D prepare(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /** Fill a rounded surface ({@code radius} is the corner radius, not the arc diameter). */
    public static void fillRound(Graphics2D g2, int x, int y, int width, int height, int radius,
                                 Color fill) {
        g2.setColor(fill);
        g2.fill(new RoundRectangle2D.Float(x, y, width, height, radius * 2f, radius * 2f));
    }

    /** Stroke a 1px rounded border, inset so the stroke stays fully inside the bounds. */
    public static void strokeRound(Graphics2D g2, int x, int y, int width, int height, int radius,
                                   Color border) {
        g2.setColor(border);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, width - 1f, height - 1f,
                radius * 2f, radius * 2f));
    }

    /** A downward chevron (dropdown marker), centered on {@code (cx, cy)}. */
    public static void paintChevronDown(Graphics2D g2, int cx, int cy, int halfWidth, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(cx - halfWidth, cy - halfWidth / 2 - 1, cx, cy + halfWidth / 2);
        g2.drawLine(cx, cy + halfWidth / 2, cx + halfWidth, cy - halfWidth / 2 - 1);
    }

    /** An upward chevron (open-the-drawer marker), centered on {@code (cx, cy)}. */
    public static void paintChevronUp(Graphics2D g2, int cx, int cy, int halfWidth, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(cx - halfWidth, cy + halfWidth / 2, cx, cy - halfWidth / 2 - 1);
        g2.drawLine(cx, cy - halfWidth / 2 - 1, cx + halfWidth, cy + halfWidth / 2);
    }

    /**
     * Mix {@code base} towards {@code target} by {@code amount} (0 = base, 1 = target) — the ONE way
     * hover/pressed/selection shades are derived from an accent token instead of new hex values.
     */
    public static Color mix(Color base, Color target, float amount) {
        float keep = 1f - amount;
        return new Color(
                Math.round(base.getRed() * keep + target.getRed() * amount),
                Math.round(base.getGreen() * keep + target.getGreen() * amount),
                Math.round(base.getBlue() * keep + target.getBlue() * amount));
    }
}

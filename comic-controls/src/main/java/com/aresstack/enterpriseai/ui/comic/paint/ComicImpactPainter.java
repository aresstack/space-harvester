package com.aresstack.enterpriseai.ui.comic.paint;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import java.awt.BasicStroke;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Paints the comic "impact" accent used for interactive moments (e.g. a hovered top-level menu):
 * a wide, softly rounded plate with a FEW controlled points — deliberately no wild star burst —
 * filled with a yellow→orange gradient, outlined in dark ink and backed by a small red offset
 * shadow. The geometry is fully deterministic so repeated hovers look identical and calm.
 */
public final class ComicImpactPainter {

    /** How far the points protrude beyond the rounded plate (also the plate's inset). */
    private static final int POINT_PROTRUSION = 4;
    private static final int SHADOW_OFFSET = 2;
    private static final float OUTLINE_WIDTH = 1.8f;
    private static final float CORNER_ARC = 10f;

    private final ComicPalette palette;

    public ComicImpactPainter(ComicPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
    }

    /** Paint the impact accent so that shape AND shadow stay inside {@code width × height}. */
    public void paint(Graphics2D g2, int width, int height) {
        Shape shape = impactShape(width, height);
        if (shape == null) {
            return;
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.translate(SHADOW_OFFSET, SHADOW_OFFSET);
        g2.setColor(palette.getAccentRed());
        g2.fill(shape);
        g2.translate(-SHADOW_OFFSET, -SHADOW_OFFSET);
        g2.setPaint(new GradientPaint(0f, 0f, palette.getAccentYellow(),
                0f, height, palette.getAccentOrange()));
        g2.fill(shape);
        g2.setColor(palette.getInk());
        g2.setStroke(new BasicStroke(OUTLINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(shape);
    }

    /**
     * The impact plate for these bounds: a rounded rectangle with one point on each edge (left,
     * right, top, bottom), or {@code null} when the bounds are too small to paint anything sane.
     */
    public Shape impactShape(int width, int height) {
        int left = 1;
        int top = 1;
        int right = width - 1 - SHADOW_OFFSET;
        int bottom = height - 1 - SHADOW_OFFSET;
        int p = POINT_PROTRUSION;
        float plateWidth = right - left - 2f * p;
        float plateHeight = bottom - top - 2f * p;
        if (plateWidth < 8f || plateHeight < 6f) {
            return null;
        }
        Area area = new Area(new RoundRectangle2D.Float(
                left + p, top + p, plateWidth, plateHeight, CORNER_ARC, CORNER_ARC));
        float midY = (top + bottom) / 2f;
        area.add(triangle(left + p + 2, midY - 5, left, midY, left + p + 2, midY + 5));
        area.add(triangle(right - p - 2, midY - 5, right, midY, right - p - 2, midY + 5));
        float topX = left + (right - left) * 0.72f;
        area.add(triangle(topX - 6, top + p + 2, topX, top, topX + 6, top + p + 2));
        float bottomX = left + (right - left) * 0.28f;
        area.add(triangle(bottomX - 6, bottom - p - 2, bottomX, bottom, bottomX + 6, bottom - p - 2));
        return area;
    }

    private static Area triangle(float x1, float y1, float x2, float y2, float x3, float y3) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.closePath();
        return new Area(path);
    }
}

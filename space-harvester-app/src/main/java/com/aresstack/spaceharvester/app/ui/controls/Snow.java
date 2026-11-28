package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.Random;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Dezenter Schnee für eine Leiste (Angelo 28.11.2026): ein paar blasse Schneeflocken auf ihrer Fläche und flache
 * Schneehäufchen auf ihrer Unterkante. Alles steht still und liegt hinter den Knöpfen; die Plätze hängen nur an der
 * Stelle in der Leiste, nicht an deren Breite, damit beim Ziehen des Fensters nichts springt.
 */
public final class Snow {

    /** Waagerechter Abstand zweier Flocken. */
    static final int FLAKE_STEP = 96;
    /** Kleinster und größter Halbmesser einer Flocke. */
    static final int FLAKE_SMALL = 4;
    static final int FLAKE_LARGE = 7;
    /** Deckkraft der Flocken (0 bis 255): blass genug, um hinter Text nicht zu stören. */
    static final int FLAKE_ALPHA = 70;
    /** Waagerechter Abstand zweier Schneehäufchen und ihre Maße. */
    static final int MOUND_STEP = 210;
    static final int MOUND_NARROW = 34;
    static final int MOUND_WIDE = 70;
    static final int MOUND_HEIGHT = 5;
    /** Startwert der festen Verteilung. */
    static final long SEED = 2412L;

    private Snow() {
    }

    /** Zeichnet den Schnee einer Leiste der Größe {@code width} mal {@code height}, wenn Weihnachtszeit ist. */
    public static void paint(Graphics graphics, int width, int height) {
        paint(graphics, width, height, box -> true);
    }

    /**
     * Wie {@link #paint(Graphics, int, int)}, aber nur dort, wo {@code free} Platz meldet: So bleibt der Schnee von
     * Knöpfen, Text und Feldern der Leiste weg.
     */
    public static void paint(Graphics graphics, int width, int height,
            java.util.function.Predicate<java.awt.Rectangle> free) {
        if (!Season.christmasNow() || width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Color flake = new Color(Comic.BLUE.getRed(), Comic.BLUE.getGreen(), Comic.BLUE.getBlue(), FLAKE_ALPHA);
            Random random = new Random(SEED);
            for (int x = FLAKE_STEP / 2; x < width; x += FLAKE_STEP) {
                int radius = FLAKE_SMALL + random.nextInt(FLAKE_LARGE - FLAKE_SMALL + 1);
                int cx = x + random.nextInt(FLAKE_STEP / 2) - FLAKE_STEP / 4;
                int cy = radius + 2 + random.nextInt(Math.max(1, height - 2 * radius - MOUND_HEIGHT - 4));
                double turn = random.nextDouble() * Math.PI / 3;
                if (free.test(new java.awt.Rectangle(cx - radius, cy - radius, 2 * radius, 2 * radius))) {
                    flake(g, cx, cy, radius, turn, flake);
                }
            }
            random = new Random(SEED);
            for (int x = MOUND_STEP / 3; x < width; x += MOUND_STEP) {
                int w = MOUND_NARROW + random.nextInt(MOUND_WIDE - MOUND_NARROW + 1);
                int left = x + random.nextInt(MOUND_STEP / 3);
                if (free.test(new java.awt.Rectangle(left, height - MOUND_HEIGHT - 2, w, MOUND_HEIGHT + 1))) {
                    mound(g, left, height, w);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Eine sechsarmige Flocke mit kleinen Zweigen an jedem Arm. */
    private static void flake(Graphics2D g, int cx, int cy, int radius, double turn, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int arm = 0; arm < 6; arm++) {
            double angle = turn + arm * Math.PI / 3;
            double dx = Math.cos(angle);
            double dy = Math.sin(angle);
            g.draw(new Line2D.Double(cx, cy, cx + dx * radius, cy + dy * radius));
            double bx = cx + dx * radius * 0.6;
            double by = cy + dy * radius * 0.6;
            for (int side = -1; side <= 1; side += 2) {
                double twig = angle + side * Math.PI / 4;
                g.draw(new Line2D.Double(bx, by, bx + Math.cos(twig) * radius * 0.35,
                        by + Math.sin(twig) * radius * 0.35));
            }
        }
    }

    /** Ein flaches Häufchen, das auf der Unterkante liegt: weiß mit zartem Rand. */
    private static void mound(Graphics2D g, int left, int bottom, int width) {
        Path2D shape = new Path2D.Double();
        shape.moveTo(left, bottom);
        shape.curveTo(left + width * 0.2, bottom - MOUND_HEIGHT * 1.3, left + width * 0.45, bottom - MOUND_HEIGHT * 1.2,
                left + width * 0.6, bottom - MOUND_HEIGHT * 0.6);
        shape.curveTo(left + width * 0.75, bottom - MOUND_HEIGHT * 1.1, left + width * 0.9, bottom - MOUND_HEIGHT * 0.5,
                left + width, bottom);
        shape.closePath();
        g.setColor(AppColors.of("app.Snow.mound"));
        g.fill(shape);
        g.setColor(Comic.LINE);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
    }
}

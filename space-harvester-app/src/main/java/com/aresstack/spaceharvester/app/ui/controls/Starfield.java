package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import java.util.Random;
import javax.swing.JPanel;
import javax.swing.Timer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Blick aus dem Raumschiff (#1141): Sterne kommen aus der Tiefe auf den Betrachter zu und ziehen am Rand als Streifen
 * vorbei. Reines Java2D, keine native Grafik. Die Animation läuft nur, solange die Fläche auf dem Bildschirm ist
 * ({@link #isShowing()}), und steht sonst still; {@link Mode#DRIFT} lässt die Sterne kaum merklich treiben,
 * {@link Mode#STILL} zeichnet nur ein ruhiges Sternenbild.
 */
public final class Starfield extends JPanel {

    /** Wie schnell der Flug geht. */
    public enum Mode {
        /** Flug mit Streifen, für Mission Control. */
        WARP(0.42),
        /** Langsames Treiben, etwa hinter einer Arbeitsfläche. */
        DRIFT(0.035),
        /** Stehendes Bild, keine Animation. */
        STILL(0);

        private final double speed;

        Mode(double speed) {
            this.speed = speed;
        }
    }

    /** Hintergrund oben/unten: tiefes Nachtblau wie der Sternenhimmel des Logos. */
    static final Color SPACE_TOP = AppColors.of("app.Starfield.spaceTop");
    static final Color SPACE_BOTTOM = AppColors.of("app.Starfield.spaceBottom");
    private static final int STARS = 520;
    private static final int FRAME_MS = 16;
    /** Brennweite relativ zur kürzeren Seite. */
    private static final double FOCAL = 0.55;
    private static final double NEAR = 0.04;

    private final Mode mode;
    private final double[] x = new double[STARS];
    private final double[] y = new double[STARS];
    private final double[] z = new double[STARS];
    private final float[] tint = new float[STARS];
    private final Random random;
    private final Timer timer;
    private long lastFrame;
    private String headline;
    private String caption;
    private boolean cockpit;

    public Starfield(Mode mode) {
        this(mode, new Random());
    }

    /** Mit festem Zufall, damit Bildschirmfotos in Tests gleich aussehen. */
    public Starfield(Mode mode, Random random) {
        this.mode = mode;
        this.random = random;
        setOpaque(true);
        setBackground(SPACE_TOP);
        setForeground(AppColors.of("app.Starfield.starWhite"));
        for (int i = 0; i < STARS; i++) {
            spawn(i, random.nextDouble());
        }
        timer = new Timer(FRAME_MS, e -> step());
        timer.setCoalesce(true);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                running(isShowing());
            }
        });
        getAccessibleContext().setAccessibleName("Sternenflug");
    }

    public Starfield headline(String text) {
        headline = text;
        getAccessibleContext().setAccessibleName(text);
        repaint();
        return this;
    }

    public Starfield caption(String text) {
        caption = text;
        getAccessibleContext().setAccessibleDescription(text);
        repaint();
        return this;
    }

    /** Ein Cockpitfenster mit Streben vor dem Sternenflug. */
    public Starfield cockpit(boolean shown) {
        cockpit = shown;
        repaint();
        return this;
    }

    public Mode mode() {
        return mode;
    }

    /** Ob die Animation gerade läuft (nur sichtbar und nicht {@link Mode#STILL}). */
    public boolean animating() {
        return timer.isRunning();
    }

    private void running(boolean on) {
        if (on && mode != Mode.STILL) {
            lastFrame = System.nanoTime();
            timer.start();
        } else {
            timer.stop();
        }
    }

    /** Ein Stern an einer neuen Stelle in der Tiefe {@code depth} (0 = ganz nah, 1 = ganz fern). */
    private void spawn(int i, double depth) {
        // Gleichmäßig im Blickfeld verteilt; ein paar Sterne nahe der Mitte, damit der Fluchtpunkt nicht leer bleibt.
        x[i] = (random.nextDouble() * 2 - 1) * 1.6;
        y[i] = (random.nextDouble() * 2 - 1) * 1.0;
        z[i] = NEAR + depth * (1 - NEAR);
        float t = random.nextFloat();
        // Meist weiß, manche bläulich, wenige warmgelb wie die Akzentfarbe.
        tint[i] = t;
    }

    /** Ein Bild weiter: die Sterne rücken um die vergangene Zeit näher. */
    void step() {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        advance(seconds);
        repaint();
    }

    /** Bewegt alle Sterne um {@code seconds} Sekunden Flug (für Tests ohne Uhr). */
    void advance(double seconds) {
        double dz = mode.speed * seconds;
        for (int i = 0; i < STARS; i++) {
            z[i] -= dz;
            if (z[i] <= NEAR || offScreen(i)) {
                spawn(i, 0.85 + random.nextDouble() * 0.15);
            }
        }
    }

    private boolean offScreen(int i) {
        double sx = x[i] / z[i] * FOCAL;
        double sy = y[i] / z[i] * FOCAL;
        return Math.abs(sx) > 1.2 || Math.abs(sy) > 1.0;
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(640, 400);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            paintSpace(g, w, h);
            paintStars(g, w, h);
            if (cockpit) {
                paintCockpit(g, w, h);
            }
            paintTexts(g, w, h);
        } finally {
            g.dispose();
        }
    }

    private void paintSpace(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, SPACE_TOP, 0, h, SPACE_BOTTOM));
        g.fillRect(0, 0, w, h);
        // Ein schwacher Nebel um den Fluchtpunkt.
        float radius = Math.max(w, h) * 0.45f;
        g.setPaint(new RadialGradientPaint(w / 2f, h / 2f, radius, new float[] {0f, 0.55f, 1f},
                new Color[] {AppColors.of("app.Starfield.nebulaBlue"), AppColors.of("app.Starfield.nebulaViolet"), new Color(0, 0, 0, 0)}));
        g.fillRect(0, 0, w, h);
    }

    private void paintStars(Graphics2D g, int w, int h) {
        double cx = w / 2.0;
        double cy = h / 2.0;
        double focal = Math.min(w, h) * FOCAL;
        // Streifenlänge: wie weit der Stern in einem Augenblick fliegt; im Stillstand nur Punkte.
        double trail = mode.speed * 0.09;
        for (int i = 0; i < STARS; i++) {
            double depth = z[i];
            double sx = cx + x[i] / depth * focal;
            double sy = cy + y[i] / depth * focal;
            if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) {
                continue;
            }
            double nearness = 1 - (depth - NEAR) / (1 - NEAR);
            boolean warp = mode == Mode.WARP;
            // Im Flug leuchten nahe Sterne auf; treibend oder stehend ist der Himmel ein ruhiges Punktbild.
            float alpha = (float) Math.min(1, (warp ? 0.15 : 0.35) + nearness * nearness * 1.1);
            float size = (float) ((warp ? 0.6 : 1.1) + nearness * nearness * 2.6);
            g.setColor(withAlpha(color(tint[i]), alpha));
            if (warp) {
                double back = Math.min(1, depth + trail);
                double px = cx + x[i] / back * focal;
                double py = cy + y[i] / back * focal;
                g.setStroke(new BasicStroke(size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(px, py, sx, sy));
            } else {
                g.fill(new java.awt.geom.Ellipse2D.Double(sx - size / 2, sy - size / 2, size, size));
            }
        }
    }

    private static Color color(float tint) {
        if (tint < 0.08f) {
            return Comic.YELLOW;
        }
        if (tint < 0.30f) {
            return AppColors.of("app.Starfield.starBlue");
        }
        return AppColors.of("app.Starfield.starWhite");
    }

    private static Color withAlpha(Color color, float alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(alpha * 255));
    }

    /** Fensterrahmen mit abgerundeten Ecken und zwei Streben nach unten, wie eine Kanzel. */
    private void paintCockpit(Graphics2D g, int w, int h) {
        int inset = Math.max(14, Math.min(w, h) / 22);
        float arc = Math.min(w, h) * 0.18f;
        RoundRectangle2D window = new RoundRectangle2D.Float(inset, inset, w - 2f * inset, h - 2f * inset, arc, arc);
        Path2D frame = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        frame.append(new java.awt.Rectangle(0, 0, w, h), false);
        frame.append(window, false);
        Color hull = AppColors.of("app.Starfield.hull");
        g.setColor(hull);
        g.fill(frame);
        // Streben von unten zur Mitte des Rahmens.
        g.setStroke(new BasicStroke(Math.max(6f, inset * 0.7f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(inset, h - inset, w * 0.30f, h * 0.70f));
        g.draw(new Line2D.Float(w - inset, h - inset, w * 0.70f, h * 0.70f));
        g.draw(new Line2D.Float(w * 0.30f, h * 0.70f, w * 0.70f, h * 0.70f));
        // Gelbe Kante innen, im Stil der Griffe der App.
        g.setColor(withAlpha(Comic.YELLOW, 0.85f));
        g.setStroke(new BasicStroke(2f));
        g.draw(window);
        // Kleine Kontrollleuchten unten im Rahmen.
        int lamp = Math.max(5, inset / 3);
        int lampY = h - inset / 2 - lamp / 2;
        Color[] lamps = {Comic.GREEN, Comic.YELLOW, AppColors.of("app.Starfield.lampBlue"), Comic.GREEN};
        for (int i = 0; i < lamps.length; i++) {
            g.setColor(lamps[i]);
            g.fillOval(w / 2 - (lamps.length * lamp * 3) / 2 + i * lamp * 3, lampY, lamp, lamp);
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private void paintTexts(Graphics2D g, int w, int h) {
        if (headline == null && caption == null) {
            return;
        }
        int baseline = (int) (h * (cockpit ? 0.42 : 0.47));
        if (headline != null) {
            Font font = Comic.semiBold(Math.max(22f, Math.min(48f, w / 18f)))
                    .deriveFont(Map.of(TextAttribute.TRACKING, 0.28f));
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int textX = (w - metrics.stringWidth(headline)) / 2;
            g.setColor(AppColors.of("app.Starfield.captionShadow"));
            g.drawString(headline, textX + 2, baseline + 2);
            g.setColor(AppColors.of("app.Starfield.captionInk"));
            g.drawString(headline, textX, baseline);
            baseline += metrics.getDescent() + 8;
            g.setColor(withAlpha(Comic.YELLOW, 0.9f));
            g.fillRect(w / 2 - 36, baseline, 72, 3);
            baseline += 14;
        }
        if (caption != null) {
            g.setFont(Comic.regular(Math.max(13f, Math.min(17f, w / 50f))));
            FontMetrics metrics = g.getFontMetrics();
            baseline += metrics.getAscent();
            g.setColor(AppColors.of("app.Starfield.captionMuted"));
            g.drawString(caption, (w - metrics.stringWidth(caption)) / 2, baseline);
        }
    }
}

package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.IntSupplier;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Eine Fläche, die sich als Ganzes gewählt zeigen kann (Angelo 28.11.2026: „Alles auswählen“ färbt nicht nur den Kopf
 * der Wissensquellen, sondern alles darunter mit): gelb hinterlegt, mit runden Ecken, bis an ihre Ränder. Beim Wählen
 * fließt das Gelb kurz von oben nach unten wie ausgegossene Farbe, in der Mitte voraus, an den Rändern etwas
 * hinterher; beim Abwählen ist es sofort weg. Die Bewegung betrifft nur das
 * Zeichnen: Ob gewählt ist, steht sofort fest ({@link #on()}). Die Kinder müssen durchsichtig sein. Nur auf dem EDT.
 */
public final class SelectionGlow extends JPanel {

    /** Dauer des Ausbreitens von oben nach unten. */
    public static final int SPREAD_MS = 420;
    /** Wie stark die Ränder der fließenden Kante hinter der Mitte zurückbleiben (1 = gar nicht). */
    private static final double EDGE_LAG = 1.8;
    /** Höhe und Deckkraft des hellen Glanzes vor der fließenden Kante. */
    private static final int SHEEN = 48;
    private static final int SHEEN_ALPHA = 110;
    /** Abstand der Bilder der Bewegung. */
    private static final int FRAME_MS = 15;
    /** Rundung der Ecken. */
    public static final int ARC = 14;

    private final Timer spread = new Timer(FRAME_MS, event -> step());
    private IntSupplier extent = this::getHeight;
    private boolean on;
    private long started;
    private float shown;

    public SelectionGlow() {
        super(new BorderLayout());
        setOpaque(false);
    }

    /** Bis zu welcher Höhe (in dieser Fläche) das Gelb reicht, etwa bis unter die letzte Zeile eines Baums. */
    public void extent(IntSupplier extent) {
        this.extent = extent;
    }

    /** Zeigt die Fläche gewählt oder nicht. */
    public void on(boolean on) {
        if (this.on == on) {
            return;
        }
        this.on = on;
        spread.stop();
        if (on && isShowing()) {
            started = System.currentTimeMillis();
            shown = 0f;
            spread.start();
        } else {
            shown = on ? 1f : 0f;
        }
        repaint();
    }

    public boolean on() {
        return on;
    }

    /** Hält die Bewegung an einer Stelle an (0 = nichts, 1 = am Ziel), für Tests und Bilder. */
    public void frame(float fraction) {
        spread.stop();
        shown = Math.max(0f, Math.min(1f, fraction));
        repaint();
    }

    /** Wie weit das Gelb gerade geflossen ist (0 bis 1). */
    public float shown() {
        return shown;
    }

    private void step() {
        shown = Math.min(1f, (System.currentTimeMillis() - started) / (float) SPREAD_MS);
        if (shown >= 1f) {
            spread.stop();
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!on) {
            return;
        }
        int full = Math.min(getHeight(), extent.getAsInt());
        if (full <= 0 || shown <= 0f) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Comic.YELLOW);
            if (shown >= 1f) {
                g.fillRoundRect(0, 0, getWidth(), full, ARC, ARC);
                return;
            }
            // Unterwegs: oben schon die runde Fläche, unten eine gewölbte Kante. Die Mitte läuft voraus und wird
            // zum Ende hin langsamer, die Ränder holen auf, bis die Kante gerade ist.
            double eased = 1 - Math.pow(1 - shown, 3);
            float middle = (float) (full * eased);
            float sides = (float) (full * Math.pow(eased, EDGE_LAG));
            float width = getWidth();
            java.awt.geom.Path2D.Float pool = new java.awt.geom.Path2D.Float();
            pool.moveTo(0, sides);
            pool.quadTo(width / 2f, 2 * middle - sides, width, sides);
            pool.lineTo(width, 0);
            pool.lineTo(0, 0);
            pool.closePath();
            java.awt.geom.Area area = new java.awt.geom.Area(pool);
            area.intersect(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(0, 0, width, full, ARC,
                    ARC)));
            g.fill(area);
            // Die Farbe ist unterwegs nicht einheitlich: Vor der Kante staut sie sich heller, wie eine Flüssigkeit
            // mit Glanz an ihrem Rand; am Ziel ist die Fläche wieder ruhig.
            float front = Math.max(middle, sides);
            g.setClip(area);
            g.setPaint(new java.awt.LinearGradientPaint(0f, Math.max(0f, front - SHEEN), 0f, front + 1f,
                    new float[] {0f, 0.7f, 1f}, new java.awt.Color[] {new java.awt.Color(255, 255, 255, 0),
                    new java.awt.Color(255, 255, 255, SHEEN_ALPHA), new java.awt.Color(255, 255, 255, 0)}));
            g.fillRect(0, (int) Math.max(0f, front - SHEEN), getWidth(), SHEEN + 2);
        } finally {
            g.dispose();
        }
    }
}

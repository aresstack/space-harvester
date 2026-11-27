package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Die Skala rund um einen Vorlese-Knopf ({@link PodcastOrb}), wie ein Zifferblatt mit zwei Zeigern (Angelo 27.11.2026:
 * „wie eine Uhr, wo man den Zeiger verstellen kann“):
 *
 * <ul>
 *   <li><b>Obere Hälfte: Sprechgeschwindigkeit.</b> Der Zeiger steht oben bei normal; nach links bis 9 Uhr langsamer
 *   bis zum langsamsten Wert, nach rechts bis 3 Uhr schneller bis zum schnellsten. Ziehen stellt sofort um, ein
 *   Doppelklick auf die obere Hälfte setzt auf normal zurück.</li>
 *   <li><b>Untere Hälfte: springen.</b> Der Zeiger steht unten; nach links gezogen springt das Vorlesen beim Loslassen
 *   satzweise zurück, nach rechts vor, je weiter, desto mehr Sätze. Danach federt er nach unten zurück.</li>
 *   <li>Eingeblendet nur bei laufender Wiedergabe und erst, wenn die Maus auf den Knopf kommt; ausgeblendet erst, wenn
 *   sie den Skalenbereich verlässt (Nachlauf, sonst wäre die Skala nicht zu bedienen), sofort bei Pause oder Ende.</li>
 * </ul>
 *
 * Die Skala liegt über allem im Fenster ({@link JLayeredPane#POPUP_LAYER}); die Mitte bleibt frei, Klicks dort gehören
 * dem Knopf. Was eine Änderung bewirkt, entscheidet der Besitzer. Alles auf dem EDT.
 */
public final class ReadAloudDial extends JComponent {

    static final String TOOLTIP = "Oben: Sprechgeschwindigkeit (Doppelklick = normal) · unten: satzweise springen";
    /** Abstand der Skala vom Knopfrand und Breite des Rings. */
    static final int GAP = 3;
    static final int RING = 22;
    /** Platz über und unter dem Ring für die Werte. */
    static final int LABEL = 16;
    /** So weit darf die Maus über den Ring hinaus, bevor die Skala verschwindet. */
    static final int HYSTERESIS = 12;
    private static final int KNOB = 6;
    private static final double QUARTER = 90.0;

    /** Was gerade gezogen wird. */
    private enum Drag { NONE, RATE, JUMP }

    private final PodcastOrb orb;
    private final DoubleConsumer rateChanged;
    private final IntConsumer jump;
    private final AWTEventListener follower = this::followMouse;
    /** Läuft vor jedem Einblenden (etwa um geänderte Grenzen aus den Einstellungen zu übernehmen). */
    private Runnable beforeShow = () -> { };
    private double min = 0.5;
    private double max = 2.0;
    private double step = 0.05;
    private int jumpMax = 10;
    private double rate = 1.0;
    private boolean active;
    private Drag drag = Drag.NONE;
    /** Ausschlag des unteren Zeigers während des Ziehens, in Sätzen. */
    private int sentences;
    /** Winkel des unteren Zeigers während des Ziehens (Grad ab unten, links negativ). */
    private double jumpAngle;

    /**
     * @param orb         der Knopf, um den die Skala liegt
     * @param rateChanged bekommt die neue Geschwindigkeit (Faktor) beim Ziehen
     * @param jump        bekommt beim Loslassen die Sätze, um die gesprungen wird (negativ = zurück)
     */
    public ReadAloudDial(PodcastOrb orb, DoubleConsumer rateChanged, IntConsumer jump) {
        this.orb = Objects.requireNonNull(orb, "orb");
        this.rateChanged = Objects.requireNonNull(rateChanged, "rateChanged");
        this.jump = Objects.requireNonNull(jump, "jump");
        setName("readAloud.dial");
        setOpaque(false);
        setVisible(false);
        setToolTipText(TOOLTIP);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int size = diameter();
        setSize(size, size + 2 * LABEL);
        MouseAdapter onOrb = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                offer();
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                offer();
            }
        };
        orb.addMouseListener(onOrb);
        orb.addMouseMotionListener(onOrb);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                drag = event.getY() < centerY() ? Drag.RATE : Drag.JUMP;
                dragTo(event.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                dragTo(event.getPoint());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                release();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2
                        && event.getY() < centerY()) {
                    setRateAndReport(1.0);
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Die Grenzen der Skala: Geschwindigkeit links/rechts (Faktor), Raster und Sätze am Ende der unteren Hälfte. */
    public void setLimits(double min, double max, double step, int jumpSentences) {
        this.min = Math.min(1.0, min);
        this.max = Math.max(1.0, max);
        this.step = step > 0 ? step : this.step;
        this.jumpMax = Math.max(1, jumpSentences);
        this.rate = clampRate(rate);
        repaint();
    }

    /** Läuft vor jedem Einblenden, etwa um geänderte Grenzen zu übernehmen. */
    public void beforeShow(Runnable action) {
        this.beforeShow = action == null ? () -> { } : action;
    }

    /** Zeigt {@code rate}, ohne sie zu melden. */
    public void setRate(double rate) {
        this.rate = clampRate(rate);
        repaint();
    }

    public double rate() {
        return rate;
    }

    /** Ob gerade wiedergegeben wird: nur dann darf die Skala erscheinen; sonst verschwindet sie sofort. */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            close();
        }
    }

    /** Ob die Skala gerade zu sehen ist (für Tests). */
    public boolean shown() {
        return isVisible() && getParent() != null;
    }

    /** Die Maus ist auf dem Knopf: bei laufender Wiedergabe die Skala zeigen. */
    void offer() {
        if (!active || !orb.isShowing()) {
            return;
        }
        JRootPane root = SwingUtilities.getRootPane(orb);
        if (root == null) {
            return;
        }
        JLayeredPane layers = root.getLayeredPane();
        if (getParent() != layers) {
            beforeShow.run();
            layers.add(this, JLayeredPane.POPUP_LAYER);
            Toolkit.getDefaultToolkit().addAWTEventListener(follower,
                    AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }
        place(layers);
        setVisible(true);
        repaint();
    }

    /** Mittig über den Knopf, auch wenn er inzwischen anders liegt (Rollen, Fenstergröße). */
    private void place(JLayeredPane layers) {
        Point center = SwingUtilities.convertPoint(orb, orb.getWidth() / 2, orb.getHeight() / 2, layers);
        setLocation(center.x - getWidth() / 2, center.y - getHeight() / 2);
    }

    /** Blendet die Skala aus; idempotent. */
    void close() {
        drag = Drag.NONE;
        sentences = 0;
        Toolkit.getDefaultToolkit().removeAWTEventListener(follower);
        if (getParent() != null) {
            java.awt.Container parent = getParent();
            parent.remove(this);
            parent.repaint(getX(), getY(), getWidth(), getHeight());
        }
        setVisible(false);
    }

    /** Nachlauf: verschwindet erst, wenn die Maus den Skalenbereich verlassen hat (nie während des Ziehens). */
    private void followMouse(AWTEvent event) {
        if (!(event instanceof MouseEvent mouse) || !isShowing() || drag != Drag.NONE) {
            return;
        }
        if (mouse.getID() == MouseEvent.MOUSE_EXITED && mouse.getComponent() instanceof java.awt.Window) {
            close(); // Maus aus dem Fenster
            return;
        }
        Point at = mouse.getLocationOnScreen();
        Point mine = getLocationOnScreen();
        double dx = at.x - (mine.x + getWidth() / 2.0);
        double dy = at.y - (mine.y + getHeight() / 2.0);
        if (Math.hypot(dx, dy) > outer() + HYSTERESIS) {
            close();
        }
    }

    /** Nur der Ring und die Werte nehmen die Maus; die Mitte gehört dem Knopf. */
    @Override
    public boolean contains(int x, int y) {
        double distance = Math.hypot(x - getWidth() / 2.0, y - centerY());
        return distance > inner() && distance <= outer() + GAP;
    }

    private void dragTo(Point point) {
        double angle = angle(point);
        if (drag == Drag.RATE) {
            setRateAndReport(rateFor(Math.max(-QUARTER, Math.min(QUARTER, angle))));
        } else if (drag == Drag.JUMP) {
            jumpAngle = Math.max(-QUARTER, Math.min(QUARTER, fromBottom(angle)));
            sentences = sentencesFor(jumpAngle);
            repaint();
        }
    }

    private void release() {
        Drag was = drag;
        drag = Drag.NONE;
        int chosen = sentences;
        sentences = 0;
        jumpAngle = 0;
        repaint();
        if (was == Drag.JUMP && chosen != 0) {
            jump.accept(chosen);
        }
    }

    private void setRateAndReport(double next) {
        double clamped = clampRate(next);
        if (clamped != rate) {
            rate = clamped;
            repaint();
            rateChanged.accept(rate);
        }
    }

    /** Winkel des Punkts um die Mitte in Grad: 0 oben, im Uhrzeigersinn positiv, -180..180. */
    private double angle(Point point) {
        return Math.toDegrees(Math.atan2(point.x - getWidth() / 2.0, centerY() - point.y));
    }

    /** Winkel ab unten: links (zurück) negativ, rechts (vor) positiv. */
    static double fromBottom(double angle) {
        return angle < 0 ? -(180 + angle) : 180 - angle;
    }

    /** Geschwindigkeit zum Winkel der oberen Hälfte (-90 = langsamste, 0 = normal, 90 = schnellste). */
    double rateFor(double angle) {
        double raw = angle < 0 ? 1.0 + angle / QUARTER * (1.0 - min) : 1.0 + angle / QUARTER * (max - 1.0);
        return clampRate(raw);
    }

    /** Winkel der oberen Hälfte zur Geschwindigkeit. */
    double angleFor(double value) {
        if (value < 1.0) {
            return min >= 1.0 ? 0 : -QUARTER * (1.0 - value) / (1.0 - min);
        }
        return max <= 1.0 ? 0 : QUARTER * (value - 1.0) / (max - 1.0);
    }

    /** Sätze zum Ausschlag des unteren Zeigers. */
    int sentencesFor(double fromBottom) {
        return (int) Math.round(fromBottom / QUARTER * jumpMax);
    }

    private double clampRate(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 1.0;
        }
        double snapped = Math.round(value / step) * step;
        return Math.max(min, Math.min(max, Math.round(snapped * 1000) / 1000.0));
    }

    private static int diameter() {
        return 2 * (PodcastOrb.SIZE / 2 + GAP + RING + GAP);
    }

    private double inner() {
        return PodcastOrb.SIZE / 2.0 + 1;
    }

    private double outer() {
        return PodcastOrb.SIZE / 2.0 + GAP + RING;
    }

    private double centerY() {
        return getHeight() / 2.0;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double cx = getWidth() / 2.0;
            double cy = centerY();
            double ringInner = PodcastOrb.SIZE / 2.0 + GAP;
            double ringOuter = outer();
            double middle = (ringInner + ringOuter) / 2;
            // Ring als Scheibe mit Loch: hell, dünner Rand wie die übrigen Steuerelemente
            g.setColor(new Color(255, 255, 255, 235));
            g.setStroke(new BasicStroke((float) (ringOuter - ringInner)));
            g.draw(new Ellipse2D.Double(cx - middle, cy - middle, 2 * middle, 2 * middle));
            g.setColor(Comic.LINE);
            g.setStroke(new BasicStroke(1f));
            g.draw(new Ellipse2D.Double(cx - ringOuter, cy - ringOuter, 2 * ringOuter, 2 * ringOuter));
            g.draw(new Line2D.Double(cx - ringOuter, cy, cx - ringInner, cy));
            g.draw(new Line2D.Double(cx + ringInner, cy, cx + ringOuter, cy));
            // obere Hälfte: Spur von normal bis zum Zeiger, Striche bei den Enden und bei normal
            Color track = new Color(Comic.BLUE.getRed(), Comic.BLUE.getGreen(), Comic.BLUE.getBlue(), 90);
            g.setColor(track);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double rateAngle = angleFor(rate);
            g.draw(new Arc2D.Double(cx - middle, cy - middle, 2 * middle, 2 * middle, 90, -rateAngle, Arc2D.OPEN));
            g.setColor(Comic.MUTED);
            g.setStroke(new BasicStroke(1.2f));
            for (double tick : new double[] {-QUARTER, -QUARTER / 2, 0, QUARTER / 2, QUARTER}) {
                tick(g, cx, cy, tick, ringOuter - 4, ringOuter - 1);
            }
            // untere Hälfte: Striche je Satzschritt, Mitte unten
            int marks = Math.min(jumpMax, 10);
            for (int i = -marks; i <= marks; i++) {
                double fb = QUARTER * i / marks;
                tick(g, cx, cy, fb < 0 ? -(180 + fb) : 180 - fb, ringOuter - (i == 0 ? 5 : 3), ringOuter - 1);
            }
            knob(g, cx, cy, middle, rateAngle, Comic.BLUE);
            double bottom = jumpAngle < 0 ? -(180 + jumpAngle) : 180 - jumpAngle;
            knob(g, cx, cy, middle, drag == Drag.JUMP ? bottom : 180, Comic.PETROL);
            // Zeichen in den Halbkreisen: oben langsamer/schneller, unten zurück/vor
            Font small = Comic.semiBold(10f);
            g.setFont(small);
            g.setColor(Comic.MUTED);
            text(g, "−", cx - middle - 1, cy - 7);
            text(g, "+", cx + middle + 1, cy - 7);
            text(g, "«", cx - middle - 1, cy + 11);
            text(g, "»", cx + middle + 1, cy + 11);
            pill(g, formatRate(rate), cx, LABEL / 2.0 + 1, Comic.BLUE);
            String below = drag == Drag.JUMP && sentences != 0
                    ? (sentences > 0 ? "+" : "−") + Math.abs(sentences) + (Math.abs(sentences) == 1 ? " Satz" : " Sätze")
                    : "Springen";
            pill(g, below, cx, getHeight() - LABEL / 2.0 - 1, Comic.PETROL);
        } finally {
            g.dispose();
        }
    }

    private static void tick(Graphics2D g, double cx, double cy, double angle, double from, double to) {
        double radians = Math.toRadians(angle);
        double sx = Math.sin(radians);
        double sy = -Math.cos(radians);
        g.draw(new Line2D.Double(cx + sx * from, cy + sy * from, cx + sx * to, cy + sy * to));
    }

    private static void knob(Graphics2D g, double cx, double cy, double radius, double angle, Color color) {
        double radians = Math.toRadians(angle);
        double x = cx + Math.sin(radians) * radius;
        double y = cy - Math.cos(radians) * radius;
        g.setColor(color);
        g.fill(new Ellipse2D.Double(x - KNOB, y - KNOB, 2 * KNOB, 2 * KNOB));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Ellipse2D.Double(x - KNOB, y - KNOB, 2 * KNOB, 2 * KNOB));
    }

    private static void text(Graphics2D g, String text, double x, double baseline) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (x - metrics.stringWidth(text) / 2.0), (float) baseline);
    }

    private static void pill(Graphics2D g, String text, double cx, double cy, Color color) {
        g.setFont(Comic.semiBold(10.5f));
        FontMetrics metrics = g.getFontMetrics();
        double width = metrics.stringWidth(text) + 10;
        double height = metrics.getHeight();
        RoundRectangle2D shape = new RoundRectangle2D.Double(cx - width / 2, cy - height / 2, width, height,
                height, height);
        g.setColor(Color.WHITE);
        g.fill(shape);
        g.setColor(color);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
        g.drawString(text, (float) (cx - metrics.stringWidth(text) / 2.0),
                (float) (cy - height / 2 + metrics.getAscent()));
    }

    /** „1×“, „0,75×“, „1,25×“ wie der Regler des Enterprise AI Client. */
    static String formatRate(double rate) {
        String text = String.format(Locale.GERMANY, "%.2f", rate).replaceAll("0+$", "").replaceAll(",$", "");
        return text + "×";
    }
}

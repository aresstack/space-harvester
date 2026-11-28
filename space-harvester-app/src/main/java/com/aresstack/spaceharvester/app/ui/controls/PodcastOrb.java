package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;

import javax.swing.JButton;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Der runde Play-Knopf des Podcasts oben rechts im Himmel, nach dem Vorlese-Orb des Enterprise AI Client
 * ({@code ReadAloudOrb}, 36 px): ▶ zum Abspielen, ❚❚ beim Abspielen, ▶ mit kreisendem Ring, solange der nächste Abschnitt
 * noch entsteht. Bei einem Fehler wird der Knopf selbst zum Wiederholen (Angelo 21.11.2026): blassrot mit den zwei
 * Kreispfeilen; klappt das Wiederholen, ist er wieder blau, scheitert es, blinkt er kurz dunkelrot und bleibt blassrot.
 * Gemalt, keine Emojis. Der Knopf zeigt nur den Zustand; was ein Klick tut, entscheidet sein Besitzer.
 */
public final class PodcastOrb extends JButton {

    /** Durchmesser wie der Orb des Enterprise AI Client. */
    public static final int SIZE = 36;

    static final Color FAILED = AppColors.of("app.PodcastOrb.failed");
    static final Color FAILED_EDGE = AppColors.of("app.PodcastOrb.failedEdge");
    static final Color FLASH = AppColors.of("app.PodcastOrb.flash");

    /** Was der Knopf zeigt. */
    public enum State {
        /** ▶: noch nichts gespielt oder angehalten. */
        PLAY,
        /** ❚❚: spielt gerade. */
        PAUSE,
        /** ▶ mit kreisendem Ring: wartet auf den nächsten Abschnitt. */
        WAITING,
        /** Kreispfeile auf Blassrot: abgebrochen, ein Klick wiederholt. */
        FAILED
    }

    private State state = State.PLAY;
    private double spin;
    private int flashes;
    private final Timer spinner = new Timer(60, event -> {
        spin = (spin + 18) % 360;
        repaint();
    });
    private final Timer flasher = new Timer(140, event -> {
        flashes--;
        repaint();
        if (flashes <= 0) {
            ((Timer) event.getSource()).stop();
        }
    });

    /** Was der Knopf je Zustand ansagt (Bildschirmleser). */
    private final java.util.function.Function<State, String> names;

    public PodcastOrb() {
        this(state -> switch (state) {
            case PLAY -> "Podcast abspielen";
            case PAUSE -> "Podcast anhalten";
            case WAITING -> "Podcast wird vorbereitet";
            case FAILED -> "Podcast erneut versuchen";
        });
    }

    /** Derselbe Knopf für etwas anderes als den Podcast, etwa das Vorlesen der Leseansicht. */
    public PodcastOrb(java.util.function.Function<State, String> names) {
        this.names = java.util.Objects.requireNonNull(names, "names");
        setName("updates.podcastOrb");
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setRequestFocusEnabled(false);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(SIZE, SIZE));
        setSize(SIZE, SIZE);
        setForeground(AppColors.of("app.PodcastOrb.podcastOrb"));
        updateName();
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        if (state == State.WAITING) {
            spinner.start();
        } else {
            spinner.stop();
        }
        updateName();
        repaint();
    }

    /** Das Wiederholen ist gescheitert: kurz dunkelrot blinken, danach wieder blassrot. */
    public void flashFailure() {
        setState(State.FAILED);
        flashes = 4;
        flasher.restart();
        repaint();
    }

    /** Ob gerade geblinkt wird (für Tests). */
    public boolean flashing() {
        return flasher.isRunning();
    }

    private void updateName() {
        getAccessibleContext().setAccessibleName(names.apply(state));
    }

    @Override
    public void removeNotify() {
        spinner.stop();
        flasher.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int size = Math.min(getWidth(), getHeight());
            boolean hovered = isEnabled() && (getModel().isRollover() || getModel().isPressed());
            boolean flash = flashes > 0 && flashes % 2 == 0;
            Color fill = !isEnabled() ? Comic.LINE : state == State.FAILED ? (flash ? FLASH : FAILED)
                    : hovered ? Comic.BLUE.darker() : Comic.BLUE;
            g.setColor(fill);
            g.fillOval(1, 1, size - 2, size - 2);
            g.setColor(state == State.FAILED ? FAILED_EDGE : fill.darker());
            g.setStroke(new BasicStroke(1.3f));
            g.drawOval(1, 1, size - 3, size - 3);
            Color ink = state == State.FAILED && !flash ? FLASH : AppColors.of("app.PodcastOrb.paintComponent");
            g.setColor(ink);
            double c = size / 2.0;
            switch (state) {
                case PLAY, WAITING -> {
                    Path2D triangle = new Path2D.Double();
                    triangle.moveTo(c - size * 0.12, c - size * 0.18);
                    triangle.lineTo(c + size * 0.2, c);
                    triangle.lineTo(c - size * 0.12, c + size * 0.18);
                    triangle.closePath();
                    g.fill(triangle);
                    if (state == State.WAITING) {
                        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(new Arc2D.Double(4, 4, size - 8, size - 8, -spin, 90, Arc2D.OPEN));
                    }
                }
                case PAUSE -> {
                    double w = size * 0.11;
                    double h = size * 0.36;
                    g.fill(new RoundRectangle2D.Double(c - w * 1.5, c - h / 2, w, h, 2, 2));
                    g.fill(new RoundRectangle2D.Double(c + w * 0.5, c - h / 2, w, h, 2, 2));
                }
                case FAILED -> {
                    // Die zwei Kreispfeile der App (Glyph.SYNC), auf Knopfgröße gezogen.
                    Graphics2D arrows = (Graphics2D) g.create();
                    try {
                        double scale = size * 0.6 / 16.0;
                        arrows.translate(c - 8 * scale, c - 8 * scale);
                        arrows.scale(scale, scale);
                        quietForeground = ink;
                        Glyph.SYNC.paintIcon(this, arrows, 0, 0);
                        quietForeground = null;
                    } finally {
                        arrows.dispose();
                    }
                }
                default -> {
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Glyph malt in der Vordergrundfarbe; nur während des Malens der Kreispfeile gesetzt. */
    private Color quietForeground;

    @Override
    public Color getForeground() {
        return quietForeground != null ? quietForeground : super.getForeground();
    }
}

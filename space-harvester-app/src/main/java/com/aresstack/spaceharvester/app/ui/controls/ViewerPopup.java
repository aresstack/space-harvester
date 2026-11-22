package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Betrachter ohne Fensterrahmen (Angelo 22.11.2026): Der Inhalt liegt in einem schlichten Darstellungsrahmen über dem
 * Fenster, wächst von der Stelle auf, an der das Bild im Text sitzt, und schrumpft beim Schließen dorthin zurück. Ein
 * Klick außerhalb, die Taste Esc oder der Esc-Kreis oben rechts schließen ihn, wie man es von Webseiten kennt.
 *
 * <p>Er ist kein eigenes Fenster und hält nichts an: Das Programm kann ihn öffnen und wieder schließen, während
 * anderes weiterläuft (der Podcast öffnet so das Bild eines Kapitels und schließt es vor dem nächsten):
 *
 * <pre>{@code
 * ViewerPopup popup = ViewerPopup.open(owner, new ImageViewer(pictures, index, look), () -> bildstelleImOwner);
 * popup.onClosed(() -> ...);
 * popup.close();
 * }</pre>
 *
 * Je Fenster ist höchstens einer offen; ein neuer ersetzt den alten.
 */
public final class ViewerPopup {

    /** Dauer des Aufwachsens und Schrumpfens. Tests setzen 0: dann steht er sofort. */
    static int animationMillis = 220;
    /** Anteil des Fensters, den der offene Betrachter einnimmt. */
    static final double SHARE = 0.86;
    /** Kleinster Rand zum Fenster, damit immer Fläche zum Wegklicken bleibt. */
    static final int MARGIN = 28;
    /** Deckkraft des abgedunkelten Fensters dahinter, bei ganz offenem Betrachter. */
    private static final int SCRIM_ALPHA = 120;
    private static final int TICK = 15;
    private static final String KEY = ViewerPopup.class.getName();

    private final JLayeredPane host;
    private final Component owner;
    private final Supplier<Rectangle> origin;
    private final Scrim scrim = new Scrim();
    private final JPanel frame = new JPanel(new BorderLayout());
    private final EscCircle esc;
    private final List<Runnable> closed = new ArrayList<>();
    private final ComponentAdapter follow = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            place();
        }
    };
    private Timer timer;
    /** 0 = an der Bildstelle, 1 = ganz offen. */
    private double progress;
    private boolean closing;
    private boolean open = true;

    private ViewerPopup(JLayeredPane host, Component owner, JComponent content, Supplier<Rectangle> origin) {
        this.host = host;
        this.owner = owner;
        this.origin = origin;
        this.esc = new EscCircle(this::close, "Schließen (Esc)");
        frame.setBackground(Comic.SURFACE);
        frame.setBorder(BorderFactory.createLineBorder(Comic.INK, 2));
        frame.add(content, BorderLayout.CENTER);
        // Klicks im Rahmen gehören dem Inhalt und schließen nicht.
        frame.addMouseListener(new MouseAdapter() { });
        scrim.setLayout(null);
        scrim.add(esc);
        scrim.add(frame);
        scrim.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        scrim.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                close();
            }
        });
        host.add(scrim, JLayeredPane.MODAL_LAYER);
        host.addComponentListener(follow);
        place();
        animate(1);
        SwingUtilities.invokeLater(() -> {
            Component focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            // Hat der Inhalt den Fokus schon selbst geholt (z. B. seine Bildfläche), bleibt er dort: Seine Tasten
            // gelten nur, solange der Fokus in ihm sitzt.
            if (open && (focused == null || !SwingUtilities.isDescendingFrom(focused, frame))
                    && !content.requestFocusInWindow()) {
                scrim.requestFocusInWindow();
            }
        });
    }

    /**
     * Öffnet {@code content} über dem Fenster von {@code owner}.
     *
     * @param origin die Stelle, von der er aufwächst und zu der er zurückschrumpft, in den Koordinaten von
     *               {@code owner}; wird beim Öffnen und beim Schließen neu gefragt (der Text kann inzwischen gerollt
     *               sein). {@code null} oder ein leeres Ergebnis: Er wächst aus der Fenstermitte.
     * @return der offene Betrachter
     * @throws IllegalStateException wenn {@code owner} in keinem Fenster hängt
     */
    public static ViewerPopup open(Component owner, JComponent content, Supplier<Rectangle> origin) {
        Objects.requireNonNull(content, "content");
        JRootPane root = owner == null ? null : SwingUtilities.getRootPane(owner);
        if (root == null) {
            throw new IllegalStateException("Der Betrachter braucht ein Fenster");
        }
        current(owner).ifPresent(ViewerPopup::closeNow);
        ViewerPopup popup = new ViewerPopup(root.getLayeredPane(), owner, content, origin);
        root.putClientProperty(KEY, popup);
        return popup;
    }

    /** Der gerade offene Betrachter im Fenster von {@code owner}. */
    public static Optional<ViewerPopup> current(Component owner) {
        JRootPane root = owner == null ? null : SwingUtilities.getRootPane(owner);
        Object popup = root == null ? null : root.getClientProperty(KEY);
        return popup instanceof ViewerPopup found && found.open ? Optional.of(found) : Optional.empty();
    }

    /** Schließt animiert: Er schrumpft an seine Bildstelle zurück. Mehrfaches Schließen schadet nicht. */
    public void close() {
        if (!open || closing) {
            return;
        }
        closing = true;
        animate(0);
    }

    /** Offen, auch während er noch aufwächst; nicht mehr, sobald er schließt. */
    public boolean isOpen() {
        return open && !closing;
    }

    /** Läuft einmal, wenn er ganz verschwunden ist (nach dem Schrumpfen). */
    public ViewerPopup onClosed(Runnable listener) {
        closed.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Wo der Rahmen gerade steht, in den Koordinaten der Fensterfläche (für Tests und Proben). */
    Rectangle frameBounds() {
        return frame.getBounds();
    }

    /** Wie ein Klick an diese Stelle der Fensterfläche (für Tests). */
    void clickAt(Point point) {
        if (!frame.getBounds().contains(point)) {
            close();
        }
    }

    // ------------------------------------------------------------------ intern

    private void animate(double target) {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (animationMillis <= 0) {
            arrive(target);
            return;
        }
        double from = progress;
        long start = System.nanoTime();
        timer = new Timer(TICK, null);
        timer.addActionListener(event -> {
            double elapsed = (System.nanoTime() - start) / 1_000_000.0 / animationMillis;
            if (elapsed >= 1) {
                timer.stop();
                timer = null;
                arrive(target);
                return;
            }
            // Weich auslaufend: schnell los, sanft ankommen.
            double eased = 1 - Math.pow(1 - elapsed, 3);
            progress = from + (target - from) * eased;
            place();
        });
        timer.start();
    }

    private void arrive(double target) {
        progress = target;
        if (target <= 0) {
            closeNow();
        } else {
            place();
        }
    }

    private void closeNow() {
        if (!open) {
            return;
        }
        open = false;
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        host.removeComponentListener(follow);
        host.remove(scrim);
        host.repaint();
        JRootPane root = host.getRootPane();
        if (root != null && root.getClientProperty(KEY) == this) {
            root.putClientProperty(KEY, null);
        }
        if (owner != null) {
            owner.requestFocusInWindow();
        }
        for (Runnable listener : List.copyOf(closed)) {
            listener.run();
        }
    }

    /** Legt Abdunklung, Rahmen und Esc-Kreis nach dem Stand der Animation. */
    private void place() {
        if (!open) {
            return;
        }
        int width = host.getWidth();
        int height = host.getHeight();
        scrim.setBounds(0, 0, width, height);
        Rectangle full = target(width, height);
        Rectangle start = start(full);
        frame.setBounds(between(start.x, full.x), between(start.y, full.y), Math.max(1, between(start.width,
                full.width)), Math.max(1, between(start.height, full.height)));
        // Sofort auslegen, nicht erst beim nächsten Durchlauf: Der Inhalt wächst in jedem Bild der Animation mit.
        frame.doLayout();
        frame.validate();
        esc.setVisible(progress >= 1);
        esc.setBounds(width - EscCircle.DIAMETER - 8, 8, EscCircle.DIAMETER, EscCircle.DIAMETER);
        scrim.repaint();
    }

    private int between(int from, int to) {
        return (int) Math.round(from + (to - from) * progress);
    }

    static Rectangle target(int width, int height) {
        int w = Math.max(1, Math.min((int) (width * SHARE), width - 2 * MARGIN));
        int h = Math.max(1, Math.min((int) (height * SHARE), height - 2 * MARGIN));
        return new Rectangle((width - w) / 2, (height - h) / 2, w, h);
    }

    /** Die Bildstelle in den Koordinaten der Fensterfläche; ohne Angabe ein kleines Rechteck in der Mitte. */
    private Rectangle start(Rectangle full) {
        Rectangle at = origin == null ? null : origin.get();
        if (at == null || at.isEmpty() || owner == null) {
            int w = full.width / 4;
            int h = full.height / 4;
            return new Rectangle(full.x + (full.width - w) / 2, full.y + (full.height - h) / 2, w, h);
        }
        return SwingUtilities.convertRectangle(owner, at, host);
    }

    /** Dunkelt das Fenster ab, fängt alle Mausereignisse und schließt beim Klick neben den Rahmen. */
    private final class Scrim extends JComponent {

        Scrim() {
            setOpaque(false);
            setFocusable(true);
            MouseAdapter outside = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    clickAt(event.getPoint());
                }
            };
            addMouseListener(outside);
            addMouseMotionListener(outside);
            addMouseWheelListener(event -> { });
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(new Color(0, 0, 0, (int) Math.round(SCRIM_ALPHA * Math.max(0, Math.min(1, progress)))));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}

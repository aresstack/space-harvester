package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.Objects;

/**
 * Ein drehender Punktering über einem Inhalt, der noch nicht fertig ist („das kommt noch, bitte warten“): Der Inhalt
 * wird abgeblendet, bleibt aber sichtbar und bedienbar, denn der Schleier lässt die Maus durch. Mit {@link #onCancel}
 * sitzt in der Mitte des Rings eine runde, neutral gedämpfte Fläche mit der Taste „Esc“ als Beschriftung: Handcursor
 * und Tooltip (nur die Beschreibung), unter der Maus leuchtet sie kräftig rot, ein Klick bricht ab, die Taste Esc
 * ebenso (mit kurzem roten Aufleuchten), solange der Schleier zu sehen ist. Nur
 * diese Fläche fängt die Maus. Ohne Abbrechen bleibt die Mitte leer. Der Ring ist überall gleich groß. Portiert aus
 * {@code BusyOverlay} des cam-tool (Qt): zwölf Punkte an festen Plätzen, nur ihre Helligkeit wandert alle 90 ms um
 * eine Stelle weiter, so liest sich der Ring als drehend.
 *
 * <p>Die Animation läuft nur, solange der Schleier gezeigt wird und die Komponente auf dem Bildschirm ist; ein
 * verdeckter oder fertiger Schleier kostet nichts. Alle Aufrufe auf dem Event Dispatch Thread.
 */
public final class BusyOverlay extends JPanel {

    /** Punkte im Ring. */
    static final int DOTS = 12;
    /** Takt der Animation; ein Umlauf dauert {@code DOTS × TICK_MILLIS} = 1,08 s. */
    static final int TICK_MILLIS = 90;
    /** Halber Durchmesser eines Punkts (6,4 px). */
    private static final double DOT_RADIUS = 3.2;
    /** Kleinster Ringradius, auch wenn der Platz fehlt. */
    private static final double MIN_RADIUS = 14.0;
    /** Ringradius, wenn Platz ist; überall derselbe. */
    private static final double NORMAL_RADIUS = 30.0;
    /** Deckkraft des Schleiers über dem Inhalt (215 von 255). */
    static final int VEIL_ALPHA = 215;
    /** Abstand zwischen Ring und Text darunter. */
    private static final int GAP = 10;
    /** Durchmesser der Stopp-Fläche: füllt die Mitte des Rings, berührt die Punkte nicht. */
    private static final int STOP_DIAMETER = (int) Math.round(2 * (NORMAL_RADIUS - DOT_RADIUS - 5));
    /** Neutral gedämpfte Fläche in Ruhe (ein Grauton wie die verblassten Punkte) und leuchtendes Rot unter der Maus. */
    static final Color STOP_MUTED = new Color(128, 132, 140, 150);
    static final Color STOP_LIT = new Color(224, 32, 32);
    public static final String CANCEL = "Abbrechen. Was schon gefunden ist, bleibt.";
    /** Beschriftung der Fläche: die Taste selbst. */
    public static final String STOP = "Esc";
    /** So lange leuchtet die Fläche nach einem Druck auf Esc rot, bevor der Abbruch läuft. */
    static final int FLASH_MILLIS = 200;

    private final JComponent content;
    private final Veil veil = new Veil();
    private final Ring ring = new Ring();
    private final Stop stop = new Stop();
    private final JLabel caption = new JLabel();
    private final Timer timer = new Timer(TICK_MILLIS, e -> tick());
    /** Das kurze rote Aufleuchten nach Esc; läuft höchstens einmal zugleich. */
    private final Timer flash = new Timer(FLASH_MILLIS, e -> flashed());
    private boolean busy;
    private String text = "";
    private int phase;
    private Color highlight;
    private Color veilColor;
    private Runnable action;

    /** Legt den Schleier über {@code content}; anfangs nicht beschäftigt. */
    public BusyOverlay(JComponent content) {
        this.content = Objects.requireNonNull(content, "content");
        setOpaque(false);
        setLayout(new Stack());
        caption.setVisible(false);
        caption.setHorizontalAlignment(JLabel.CENTER);
        stop.setVisible(false);
        veil.add(stop);
        veil.add(ring);
        veil.add(caption);
        veil.setVisible(false);
        // Esc bricht ab, solange der Schleier zu sehen ist: Swing löst Bindungen des Fensters nur für sichtbare
        // Komponenten aus, und der Schleier ist nur bei busy sichtbar.
        veil.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, false),
                "busyCancel");
        veil.getActionMap().put("busyCancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                flashAndCancel();
            }
        });
        // Der Schleier zuerst: Index 0 liegt oben.
        add(veil);
        add(content);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                updateTimer();
            }
        });
    }

    /** Der Inhalt unter dem Schleier. */
    public JComponent content() {
        return content;
    }

    /** Zeigt bzw. nimmt den Schleier; die Animation beginnt jedes Mal von vorn. */
    public void setBusy(boolean busy) {
        if (this.busy == busy) {
            return;
        }
        this.busy = busy;
        phase = 0;
        stop.hover = false;
        // Ein Esc, das noch aufleuchtet, galt der vorigen Arbeit: Es darf die nächste nicht abbrechen.
        stopFlash();
        veil.setVisible(busy);
        updateTimer();
        revalidate();
        repaint();
    }

    public boolean isBusy() {
        return busy;
    }

    /** Text unter dem Ring; leer für keinen. */
    public void setText(String text) {
        String next = text == null ? "" : text;
        if (next.equals(this.text)) {
            return;
        }
        this.text = next;
        caption.setText(next);
        caption.setVisible(!next.isEmpty());
        veil.revalidate();
        veil.repaint();
    }

    public String text() {
        return text;
    }

    /**
     * Zeigt in der Mitte des Rings die Esc-Fläche, die {@code action} auslöst (Klick oder Esc); {@code null}
     * nimmt sie weg. Nur die Fläche fängt die Maus, der übrige Schleier lässt sie zum Inhalt durch.
     */
    public void onCancel(Runnable action) {
        this.action = action;
        stop.hover = false;
        stopFlash();
        stop.setVisible(action != null);
        veil.revalidate();
        veil.repaint();
    }

    /** Der Ring, etwa für Tests; er fängt nie die Maus. */
    public JComponent ringControl() {
        return ring;
    }

    /** Die Stopp-Fläche in der Mitte des Rings, etwa für Tests. */
    public JComponent cancelControl() {
        return stop;
    }

    /** Löst „Abbrechen“ aus, als wäre die Fläche geklickt; ohne Abbrechen oder ohne Schleier geschieht nichts. */
    public void cancel() {
        Runnable run = action;
        if (run != null && busy) {
            run.run();
        }
    }

    /**
     * Esc: Die Fläche leuchtet einmal kurz rot auf, wie unter der Maus, dann läuft der Abbruch. Ein zweiter Druck
     * während des Aufleuchtens ändert nichts.
     */
    void flashAndCancel() {
        if (action == null || !busy || stop.flash) {
            return;
        }
        stop.flash = true;
        stop.repaint();
        flash.setRepeats(false);
        flash.start();
    }

    /** Ende des Aufleuchtens: jetzt der Abbruch, sofern dieselbe Arbeit noch läuft. */
    private void flashed() {
        if (!stop.flash) {
            return;
        }
        stop.flash = false;
        stop.repaint();
        cancel();
    }

    private void stopFlash() {
        flash.stop();
        stop.flash = false;
        stop.repaint();
    }

    /** Ob die Fläche gerade nach Esc rot aufleuchtet, für Tests. */
    boolean flashing() {
        return stop.flash;
    }

    /** Ob die Stopp-Fläche im Ring gezeigt wird. */
    public boolean cancelShown() {
        return stop.isVisible();
    }

    /** Farbe der Punkte; ohne Angabe die Akzentfarbe der Oberfläche. */
    public void setHighlight(Color highlight) {
        this.highlight = highlight;
        veil.repaint();
    }

    /** Farbe des Schleiers (ohne Deckkraft); ohne Angabe die Hintergrundfarbe der Oberfläche. */
    public void setVeilColor(Color color) {
        this.veilColor = color;
        veil.repaint();
    }

    /** Stand der Animation, für Tests. */
    int phase() {
        return phase;
    }

    boolean animating() {
        return timer.isRunning();
    }

    /** Ob die Stopp-Fläche gerade unter der Maus liegt, für Tests. */
    boolean hovering() {
        return stop.hover;
    }

    private void updateTimer() {
        if (busy && isShowing()) {
            if (!timer.isRunning()) {
                timer.start();
            }
        } else {
            timer.stop();
        }
    }

    private void tick() {
        phase = (phase + 1) % DOTS;
        ring.repaint();
    }

    private Color highlight() {
        if (highlight != null) {
            return highlight;
        }
        Color ui = UIManager.getColor("List.selectionBackground");
        return ui == null ? new Color(60, 140, 230) : ui;
    }

    private Color veilColor() {
        if (veilColor != null) {
            return veilColor;
        }
        Color ui = UIManager.getColor("Panel.background");
        return ui == null ? Color.LIGHT_GRAY : ui;
    }

    private Color ink() {
        Color ui = UIManager.getColor("Label.foreground");
        return ui == null ? Color.BLACK : ui;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Radius des Rings: {@link #NORMAL_RADIUS}, wenn der Platz reicht; nie unter {@link #MIN_RADIUS}. */
    private static double radius(int width, int height) {
        double fits = Math.min(width, height) / 2.0 - 6.0;
        return Math.max(MIN_RADIUS, Math.min(NORMAL_RADIUS, fits));
    }

    /** Der Schleier: blendet ab und trägt Ring, Text und Link; fängt die Maus nur in Ring und Link. */
    private final class Veil extends JComponent {

        Veil() {
            setOpaque(false);
            setLayout(new Column());
        }

        /** Nur die Stopp-Fläche gehört zum Schleier; sonst gehen Mausereignisse an den Inhalt darunter. */
        @Override
        public boolean contains(int x, int y) {
            return stop.isVisible() && stop.contains(x - stop.getX(), y - stop.getY());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                // Abblenden statt verbergen: Was da ist, bleibt lesbar, nur sichtbar noch nicht fertig.
                g.setColor(withAlpha(veilColor(), VEIL_ALPHA));
                g.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g.dispose();
            }
        }
    }

    /** Der Punktering; reine Anzeige, fängt nie die Maus. */
    private final class Ring extends JComponent {

        Ring() {
            setOpaque(false);
            setFocusable(false);
        }

        @Override
        public Dimension getPreferredSize() {
            int side = (int) Math.ceil(2 * (NORMAL_RADIUS + DOT_RADIUS)) + 4;
            return new Dimension(side, side);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double radius = radius(getWidth(), getHeight());
                double centerX = getWidth() / 2.0;
                double centerY = getHeight() / 2.0;
                Color base = highlight();
                for (int i = 0; i < DOTS; i++) {
                    // Der Punkt an der Phase ist am hellsten; die dahinter verblassen, so dreht sich der Ring.
                    int age = (i - phase + DOTS) % DOTS;
                    double fade = 1.0 - (double) age / DOTS;
                    double alpha = 0.12 + 0.88 * fade * fade;
                    double angle = 2.0 * Math.PI * i / DOTS - Math.PI / 2.0;
                    double x = centerX + radius * Math.cos(angle);
                    double y = centerY + radius * Math.sin(angle);
                    g.setColor(withAlpha(base, (int) Math.round(alpha * 255.0)));
                    g.fill(new Ellipse2D.Double(x - DOT_RADIUS, y - DOT_RADIUS, 2 * DOT_RADIUS, 2 * DOT_RADIUS));
                }
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Die Stopp-Fläche: ein neutral gedämpfter Kreis mit „Esc“ in Weiß, der die Mitte des Rings füllt; erst unter der
     * Maus leuchtend rot. Rund auch für die Maus: Nur innerhalb des Kreises fängt sie.
     */
    private final class Stop extends JComponent {

        boolean hover;
        /** Kurz rot nach Esc. */
        boolean flash;

        Stop() {
            setOpaque(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(CANCEL);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        cancel();
                    }
                }
            });
        }

        /** Ein {@link JComponent} hat von sich aus keinen Zugänglichkeitskontext; hier heißt die Fläche „Abbrechen“. */
        @Override
        public javax.accessibility.AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJComponent() {
                    @Override
                    public javax.accessibility.AccessibleRole getAccessibleRole() {
                        return javax.accessibility.AccessibleRole.PUSH_BUTTON;
                    }

                    @Override
                    public String getAccessibleName() {
                        return CANCEL;
                    }
                };
            }
            return accessibleContext;
        }

        @Override
        public boolean contains(int x, int y) {
            double dx = x - getWidth() / 2.0;
            double dy = y - getHeight() / 2.0;
            return Math.hypot(dx, dy) <= STOP_DIAMETER / 2.0;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(STOP_DIAMETER, STOP_DIAMETER);
        }

        private Font font() {
            Font base = getFont() == null ? UIManager.getFont("Label.font") : getFont();
            return base.deriveFont(Font.BOLD, 10.5f);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                double x = (getWidth() - STOP_DIAMETER) / 2.0;
                double y = (getHeight() - STOP_DIAMETER) / 2.0;
                g.setColor(hover || flash ? STOP_LIT : STOP_MUTED);
                g.fill(new Ellipse2D.Double(x, y, STOP_DIAMETER, STOP_DIAMETER));
                g.setFont(font());
                FontMetrics metrics = g.getFontMetrics();
                g.setColor(Color.WHITE);
                g.drawString(STOP, (getWidth() - metrics.stringWidth(STOP)) / 2,
                        (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g.dispose();
            }
        }
    }

    /** Ring in der Mitte des Schleiers, die Stopp-Fläche in seiner Mitte; Text darunter, zentriert. */
    private final class Column implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            Dimension size = ring.getPreferredSize();
            int x = (parent.getWidth() - size.width) / 2;
            int y = (parent.getHeight() - size.height) / 2;
            ring.setBounds(x, y, size.width, size.height);
            Dimension disc = stop.getPreferredSize();
            stop.setBounds((parent.getWidth() - disc.width) / 2, (parent.getHeight() - disc.height) / 2, disc.width,
                    disc.height);
            if (caption.isVisible()) {
                Dimension wanted = caption.getPreferredSize();
                caption.setBounds((parent.getWidth() - wanted.width) / 2, y + size.height + GAP, wanted.width,
                        wanted.height);
            }
        }
    }

    /** Schleier und Inhalt deckungsgleich über die ganze Fläche; die Wunschgröße ist die des Inhalts. */
    private final class Stack implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return content.getPreferredSize();
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return content.getMinimumSize();
        }

        @Override
        public void layoutContainer(Container parent) {
            for (Component child : parent.getComponents()) {
                child.setBounds(0, 0, parent.getWidth(), parent.getHeight());
            }
        }
    }

    /** Die Kinder überlappen: Swing darf nicht annehmen, dass sie sich nicht überdecken. */
    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;
    }
}

package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Ellipse2D;
import java.util.Objects;

/**
 * Ein drehender Punktering über einem Inhalt, der noch nicht fertig ist („das kommt noch, bitte warten“): Der Inhalt
 * wird abgeblendet, bleibt aber sichtbar und bedienbar, denn der Schleier lässt die Maus durch. In der Mitte stehen
 * wahlweise ein Text oder ein Knopf „Abbrechen“ ({@link #onCancel}), der die Maus als Einziger fängt. Portiert aus
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
    /** Ringradius, wenn Platz ist; wächst mit dem Text bzw. Knopf in der Mitte. */
    private static final double NORMAL_RADIUS = 30.0;
    /** Deckkraft des Schleiers über dem Inhalt (215 von 255). */
    static final int VEIL_ALPHA = 215;
    public static final String CANCEL = "Abbrechen";

    private final JComponent content;
    private final Veil veil = new Veil();
    private final JButton cancel = new JButton(CANCEL);
    private final Timer timer = new Timer(TICK_MILLIS, e -> tick());
    private boolean busy;
    private String text = "";
    private int phase;
    private Color highlight;
    private Color veilColor;

    /** Legt den Schleier über {@code content}; anfangs nicht beschäftigt. */
    public BusyOverlay(JComponent content) {
        this.content = Objects.requireNonNull(content, "content");
        setOpaque(false);
        setLayout(new Stack());
        cancel.setVisible(false);
        cancel.setFocusable(false);
        cancel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cancel.setToolTipText("Nicht weiter warten; was schon da ist, bleibt");
        cancel.getAccessibleContext().setAccessibleName(CANCEL);
        veil.add(cancel);
        veil.setVisible(false);
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
        veil.setVisible(busy);
        updateTimer();
        revalidate();
        repaint();
    }

    public boolean isBusy() {
        return busy;
    }

    /** Text in der Mitte des Rings; leer für keinen. Mit Knopf steht der Text darüber. */
    public void setText(String text) {
        String next = text == null ? "" : text;
        if (next.equals(this.text)) {
            return;
        }
        this.text = next;
        veil.revalidate();
        veil.repaint();
    }

    public String text() {
        return text;
    }

    /**
     * Zeigt in der Mitte des Rings den Knopf „Abbrechen“, der {@code action} auslöst; {@code null} nimmt ihn weg. Nur
     * der Knopf fängt die Maus, der übrige Schleier lässt sie zum Inhalt durch.
     */
    public void onCancel(Runnable action) {
        for (java.awt.event.ActionListener listener : cancel.getActionListeners()) {
            cancel.removeActionListener(listener);
        }
        cancel.setVisible(action != null);
        if (action != null) {
            cancel.addActionListener(e -> action.run());
        }
        veil.revalidate();
        veil.repaint();
    }

    /** Der Knopf in der Mitte, etwa für Tests oder einen anderen Text. */
    public JButton cancelButton() {
        return cancel;
    }

    /** Ob der Knopf „Abbrechen“ im Ring gezeigt wird. */
    public boolean cancelShown() {
        return cancel.isVisible();
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
        veil.repaint();
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

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Radius des Rings: um Text bzw. Knopf herum, wenn der Platz reicht; nie unter {@link #MIN_RADIUS}. */
    private double radius(FontMetrics metrics, int width, int height) {
        double wanted = metrics.stringWidth(text) / 2.0 + 20.0;
        if (cancel.isVisible()) {
            Dimension button = cancel.getPreferredSize();
            wanted = Math.max(wanted, Math.hypot(button.width / 2.0 + 14.0, button.height / 2.0 + 14.0));
        }
        double fits = Math.min(width, height) / 2.0 - 6.0;
        return Math.max(MIN_RADIUS, Math.min(Math.max(NORMAL_RADIUS, wanted), fits));
    }

    /** Der Schleier: zeichnet Abblendung, Ring und Text; fängt die Maus nur im Knopf. */
    private final class Veil extends JComponent {

        Veil() {
            setOpaque(false);
            setLayout(new Centered());
        }

        /** Nur der Knopf gehört zum Schleier; sonst gehen Mausereignisse an den Inhalt darunter. */
        @Override
        public boolean contains(int x, int y) {
            return cancel.isVisible() && cancel.getBounds().contains(x, y);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                // Abblenden statt verbergen: Was da ist, bleibt lesbar, nur sichtbar noch nicht fertig.
                g.setColor(withAlpha(veilColor(), VEIL_ALPHA));
                g.fillRect(0, 0, width, height);

                FontMetrics metrics = g.getFontMetrics(getFont());
                double radius = radius(metrics, width, height);
                double centerX = width / 2.0;
                double centerY = height / 2.0;
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

                if (!text.isEmpty()) {
                    g.setFont(getFont());
                    Color ink = UIManager.getColor("Label.foreground");
                    g.setColor(ink == null ? Color.BLACK : ink);
                    int x = (int) Math.round(centerX - metrics.stringWidth(text) / 2.0);
                    // Mit Knopf steht der Text über ihm, sonst genau in der Mitte.
                    double baseline = cancel.isVisible()
                            ? cancel.getY() - metrics.getDescent() - 6
                            : centerY - metrics.getHeight() / 2.0 + metrics.getAscent();
                    g.drawString(text, x, (int) Math.round(baseline));
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Der Knopf genau in der Mitte des Schleiers, in seiner Wunschgröße. */
    private static final class Centered implements LayoutManager {

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
            for (Component child : parent.getComponents()) {
                Dimension size = child.getPreferredSize();
                child.setBounds((parent.getWidth() - size.width) / 2, (parent.getHeight() - size.height) / 2,
                        size.width, size.height);
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

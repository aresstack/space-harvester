package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiTypography;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.function.IntConsumer;

/**
 * Die Fortschrittsanzeige des Einrichtungsassistenten in Java2D, übernommen aus dem Enterprise AI Client: je Schritt ein Comic-Kreis mit Tintenkontur und
 * Nummer (erledigt: grün mit Haken, aktuell: blau), dazwischen Verbindungslinien, darunter der Name des Schritts.
 * Zugleich Navigation: ein Klick auf einen Schritt springt dorthin, ohne Prüfung (der Gastgeber entscheidet über
 * {@link #setOnSelect(IntConsumer)}); der Haken zeigt weiterhin nur echte Erfüllung.
 */
final class SetupStepTrail extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int DIAMETER = 26;

    private final String[] names;
    private int current;
    private boolean[] done;
    private IntConsumer onSelect;
    private boolean navigable = true;

    SetupStepTrail(String[] names) {
        this.names = names.clone();
        this.done = new boolean[names.length];
        setOpaque(false);
        getAccessibleContext().setAccessibleName("Fortschritt der Einrichtung");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = stepAt(e.getX());
                if (navigable && onSelect != null && SwingUtilities.isLeftMouseButton(e) && index >= 0) {
                    onSelect.accept(index);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int index = stepAt(e.getX());
                boolean active = navigable && onSelect != null && index >= 0 && index != current;
                setCursor(active ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                setToolTipText(active ? "Zu „" + names[index] + "“ springen" : null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Wer auf Klicks reagiert (Index des Schritts); {@code null} = nur Anzeige. */
    void setOnSelect(IntConsumer callback) {
        this.onSelect = callback;
    }

    /** Ob Klicks gerade springen dürfen (nicht während einer laufenden Abfrage). */
    void setNavigable(boolean value) {
        this.navigable = value;
        if (!value) {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    /** Der Schritt unter einer x-Koordinate; -1 außerhalb. */
    int stepAt(int x) {
        if (getWidth() <= 0 || x < 0 || x >= getWidth()) {
            return -1;
        }
        return Math.min(names.length - 1, x / Math.max(1, getWidth() / names.length));
    }

    /** Mitte des Kreises eines Schritts (für Tests und Klickziele). */
    int centerOf(int index) {
        int slot = getWidth() / names.length;
        return slot * index + slot / 2;
    }

    void show(int currentStep, boolean[] completed) {
        this.current = currentStep;
        this.done = completed.clone();
        getAccessibleContext().setAccessibleDescription("Schritt " + (currentStep + 1) + " von " + names.length
                + ": " + names[currentStep]);
        repaint();
    }

    /**
     * Ein eigener Kontext: {@link JComponent} selbst liefert keinen ({@code null}), dann scheiterte schon der Aufbau
     * des Assistenten (Beschriftung im Konstruktor) und er erschien weder beim Erststart noch aus den Einstellungen.
     */
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PROGRESS_BAR;
                }
            };
        }
        return accessibleContext;
    }

    int current() {
        return current;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(names.length * 120, DIAMETER + 30);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int count = names.length;
            int slot = getWidth() / count;
            int top = 4;
            Font label = ResearchUiTypography.regular(11.5f);
            Font bold = ResearchUiTypography.semiBold(11.5f);
            for (int i = 0; i < count; i++) {
                int cx = slot * i + slot / 2;
                if (i < count - 1) {
                    g2.setColor(done[i] ? ResearchUiPalette.WAVE_GREEN : ResearchUiPalette.LIGHT_CONTROL_BORDER);
                    g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(new Line2D.Float(cx + DIAMETER / 2f + 4, top + DIAMETER / 2f,
                            cx + slot - DIAMETER / 2f - 4, top + DIAMETER / 2f));
                }
                Color fill = done[i] ? ResearchUiPalette.WAVE_GREEN
                        : i == current ? ResearchUiPalette.ACCENT_BLUE : Color.WHITE;
                Ellipse2D circle = new Ellipse2D.Float(cx - DIAMETER / 2f, top, DIAMETER, DIAMETER);
                g2.setColor(fill);
                g2.fill(circle);
                g2.setColor(Comic.INK);
                g2.setStroke(new BasicStroke(i == current ? 2.6f : 1.8f));
                g2.draw(circle);
                if (done[i]) {
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[] {cx - 6, cx - 1, cx + 7}, new int[] {top + 13, top + 18, top + 8}, 3);
                } else {
                    g2.setFont(bold.deriveFont(13f));
                    g2.setColor(i == current ? Color.WHITE : Comic.INK);
                    String number = Integer.toString(i + 1);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(number, cx - fm.stringWidth(number) / 2,
                            top + (DIAMETER - fm.getHeight()) / 2 + fm.getAscent());
                }
                g2.setFont(i == current ? bold : label);
                g2.setColor(i == current ? Comic.INK : ResearchUiPalette.LIGHT_CONTROL_TEXT);
                FontMetrics fm = g2.getFontMetrics();
                String name = names[i];
                g2.drawString(name, cx - fm.stringWidth(name) / 2, top + DIAMETER + 6 + fm.getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}

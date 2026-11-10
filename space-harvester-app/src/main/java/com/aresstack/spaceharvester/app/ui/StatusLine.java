package com.aresstack.spaceharvester.app.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Die Statuszeile unten im Hauptfenster. Ihr Hintergrund ist zugleich der Fortschrittsbalken der Aufträge: er füllt
 * sich von ganz links bis ganz rechts (auch unter „© AresStack“). Darüber ganz links ein Hinweis, der eine Eingabe
 * verlangt (Einmalcode), dann der Stand der App, mittig Schritt,
 * Abbrechen bzw. Endzustand des Auftrags, rechts das Copyright. Bei 100 % bleibt der Balken noch {@link #FADE_DELAY_MS}
 * Millisekunden stehen und verschwindet dann; der Text („Fertig“) bleibt.
 *
 * <p>Der Balken folgt dem Modell eines (nicht angezeigten) {@link JProgressBar}, den der Bereich „Aufträge“
 * fortschreibt.</p>
 */
final class StatusLine extends JPanel {

    /** So lange bleibt ein voller Balken stehen, bevor er verschwindet. */
    static final int FADE_DELAY_MS = 4000;
    /** Höchstanteil der Breite für den Stand des sichtbaren Bereichs. */
    static final double LEAD_SHARE = 0.5;
    /** Höchstanteil der Breite für alles links der Mitte zusammen (Hinweis, Stand des Bereichs, Stand der App). */
    static final double LEFT_SHARE = 0.6;
    /** Die Füllung: helles Blau, damit der Text darauf lesbar bleibt. */
    static final Color FILL = mix(Comic.BLUE, Comic.SURFACE, 0.22);

    private final JProgressBar model;
    private final Timer fade;
    private final Timer sweep;
    private boolean fillVisible;
    private int sweepOffset;
    /** Zustand beim letzten Aufruf: nur der Wechsel auf 100 % startet das Ausblenden, nicht jede Wiederholung. */
    private boolean wasFull;

    StatusLine(JProgressBar model, JComponent west, JComponent center, JComponent east) {
        this(model, null, west, center, east);
    }

    /**
     * @param lead Stand des sichtbaren Bereichs vor {@code west}; höchstens {@link #LEAD_SHARE} der Breite, darin
     *             kürzt der Bereich selbst (etwa einen langen Hinweis mit „…“), {@code null} ohne
     */
    StatusLine(JProgressBar model, JComponent lead, JComponent west, JComponent center, JComponent east) {
        this(model, null, lead, west, center, east);
    }

    /**
     * @param alert ein Hinweis ganz links vor allem anderen, der Aufmerksamkeit verlangt (etwa „Confluence verlangt
     *              einen Einmalcode“, {@link SignInNotice}); er zeigt sich selbst nur, solange er gilt, {@code null} ohne
     */
    StatusLine(JProgressBar model, JComponent alert, JComponent lead, JComponent west, JComponent center,
               JComponent east) {
        super(new BorderLayout(12, 0));
        this.model = model;
        setOpaque(true);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 16, 4, 16)));
        // Der Stand links darf die Mitte (Abbrechen, Endzustand) nicht verdrängen: höchstens 40 % der Breite,
        // ein langer Text (Pfad des Bestands) wird mit „…“ gekürzt.
        // Nur der Stand der App wird auf 40 % gekürzt; der Stand des Bereichs (mit Abbrechen) steht davor.
        if (lead == null && alert == null) {
            add(new Capped(west, 0.4), BorderLayout.WEST);
        } else {
            // FlowLayout statt BoxLayout: Es fragt die (von der Breite abhängigen) Größen jedes Mal neu ab.
            JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            left.setOpaque(false);
            if (alert != null) {
                left.add(new Capped(alert, LEAD_SHARE));
            }
            if (lead != null) {
                left.add(new Capped(lead, LEAD_SHARE));
            }
            left.add(new Capped(west, 0.4));
            // Zusammen höchstens LEFT_SHARE: Die Mitte (Abbrechen, Endzustand) und das Copyright behalten ihren
            // Platz, auch wenn Hinweis, Stand des Bereichs und Stand der App je für sich voll wären; was dann
            // rechts im linken Teil nicht mehr passt (der Stand der App), fällt weg.
            add(new Capped(left, LEFT_SHARE), BorderLayout.WEST);
        }
        if (center != null) {
            add(center, BorderLayout.CENTER);
        }
        add(east, BorderLayout.EAST);
        fade = new Timer(FADE_DELAY_MS, e -> {
            fillVisible = false;
            repaint();
        });
        fade.setRepeats(false);
        sweep = new Timer(40, e -> {
            sweepOffset = (sweepOffset + 6) % Math.max(1, getWidth() + getWidth() / 4);
            repaint();
        });
        model.addChangeListener(e -> update());
        model.addPropertyChangeListener("indeterminate", e -> update());
        update();
    }

    /** Ob gerade eine Füllung gezeichnet wird (läuft oder ist noch nicht ausgeblendet). */
    boolean fillVisible() {
        return fillVisible;
    }

    /** Anteil der Füllung zwischen 0 und 1. */
    double fraction() {
        int range = model.getMaximum() - model.getMinimum();
        return range <= 0 ? 0 : Math.max(0, Math.min(1, (model.getValue() - model.getMinimum()) / (double) range));
    }

    /** Für Tests: der Ausblende-Timer. */
    Timer fadeTimer() {
        return fade;
    }

    private void update() {
        if (model.isIndeterminate()) {
            fade.stop();
            fillVisible = true;
            if (!sweep.isRunning()) {
                sweep.start();
            }
        } else {
            sweep.stop();
            double fraction = fraction();
            if (fraction <= 0) {
                fade.stop();
                fillVisible = false;
            } else if (fraction >= 1) {
                if (!wasFull) {
                    fillVisible = true;
                    fade.restart();
                }
            } else {
                fade.stop();
                fillVisible = true;
            }
        }
        wasFull = !model.isIndeterminate() && fraction() >= 1;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!fillVisible) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(FILL);
        int top = 1;
        int height = getHeight() - top;
        if (model.isIndeterminate()) {
            int block = Math.max(40, getWidth() / 4);
            g2.fillRect(sweepOffset - block, top, block, height);
        } else {
            g2.fillRect(0, top, (int) Math.round(getWidth() * fraction()), height);
        }
        g2.dispose();
    }

    @Override
    public void removeNotify() {
        sweep.stop();
        fade.stop();
        super.removeNotify();
    }

    /** Hält ein Kind auf höchstens einen Anteil der Breite der Statuszeile. */
    private final class Capped extends JPanel {
        private final double share;

        Capped(JComponent child, double share) {
            super(new BorderLayout());
            this.share = share;
            setOpaque(false);
            add(child, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension size = super.getPreferredSize();
            int cap = (int) (StatusLine.this.getWidth() * share);
            if (cap > 0 && size.width > cap) {
                size.width = cap;
            }
            return size;
        }

        @Override
        public java.awt.Dimension getMinimumSize() {
            return new java.awt.Dimension(0, super.getMinimumSize().height);
        }
    }

    private static Color mix(Color color, Color base, double share) {
        return new Color((int) Math.round(color.getRed() * share + base.getRed() * (1 - share)),
                (int) Math.round(color.getGreen() * share + base.getGreen() * (1 - share)),
                (int) Math.round(color.getBlue() * share + base.getBlue() * (1 - share)));
    }
}

package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;

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
 * Die Statuszeile unten im Hauptfenster. Ihr Hintergrund ist zugleich der Fortschrittsbalken aller Aufträge
 * (Angelo 11.11.2026): er füllt sich von ganz links bis ganz rechts (auch unter „© AresStack“). Darüber ganz links ein
 * Hinweis, der eine Eingabe verlangt (Einmalcode), dann der Stand der App, mittig der Gesamtstand der Aufträge bzw.
 * wie ihr Durchgang endete, rechts das Copyright. Bei 100 % bleibt der Balken noch {@link #FADE_DELAY_MS}
 * Millisekunden stehen und verschwindet dann; der Text („Fertig“) bleibt.
 *
 * <p>Der Balken folgt dem Modell eines (nicht angezeigten) {@link JProgressBar}, den der Bereich „Aufträge“
 * fortschreibt.</p>
 */
final class StatusLine extends JPanel {

    /** So lange bleibt ein voller Balken stehen, bevor er verschwindet. */
    static final int FADE_DELAY_MS = 4000;
    /** Höchstanteil der Breite für den Stand des sichtbaren Bereichs, solange die Mitte den Platz braucht. */
    static final double LEAD_SHARE = 0.5;
    /**
     * Höchstanteil der Breite für alles links der Mitte zusammen (Hinweis, Stand des Bereichs, Stand der App), solange
     * die Mitte den Platz braucht; braucht sie weniger, reicht der linke Teil bis an das Copyright ({@link #leftRoom}).
     */
    static final double LEFT_SHARE = 0.6;
    /** Abstand zwischen links, Mitte und rechts. */
    private static final int GAP = 12;
    /** Die Füllung: helles Blau, damit der Text darauf lesbar bleibt. */
    static final Color FILL = mix(Comic.BLUE, Comic.SURFACE, 0.22);

    private final JProgressBar model;
    private final JComponent center;
    private final JComponent east;
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
     * @param lead Stand des sichtbaren Bereichs vor {@code west}; höchstens {@link #LEAD_SHARE} der Breite oder, wenn
     *             mehr, was links bis an das Copyright neben Hinweis, {@code west} und dem, was die Mitte braucht,
     *             frei bleibt (mindestens {@link #LEFT_SHARE}); erst darin kürzt der Bereich selbst (etwa einen langen
     *             Hinweis mit „…“, nie Rot samt Kreispfeil), {@code null} ohne
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
        super(new BorderLayout(GAP, 0));
        this.model = model;
        this.center = center;
        this.east = east;
        setOpaque(true);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 16, 4, 16)));
        // Der Stand links darf die Mitte (Stand der Aufträge, Endzustand) nicht verdrängen: höchstens 40 % der Breite,
        // ein langer Text (Pfad des Bestands) wird mit „…“ gekürzt.
        // Nur der Stand der App wird auf 40 % gekürzt; der Stand des Bereichs (mit Abbrechen) steht davor.
        if (lead == null && alert == null) {
            add(new Capped(west, 0.4) {
                @Override
                int cap() {
                    return Math.max(super.cap(), leftRoom());
                }
            }, BorderLayout.WEST);
        } else {
            // FlowLayout statt BoxLayout: Es fragt die (von der Breite abhängigen) Größen jedes Mal neu ab.
            JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            left.setOpaque(false);
            Capped alertCap = alert == null ? null : new Capped(alert, LEAD_SHARE);
            Capped westCap = new Capped(west, 0.4);
            if (alertCap != null) {
                left.add(alertCap);
            }
            if (lead != null) {
                // Der Stand des Bereichs darf über LEAD_SHARE hinaus, was im linken Teil (LEFT_SHARE) neben Hinweis und
                // Stand der App frei bleibt, etwa solange die App nichts meldet: Sonst kürzt sich ein langer Stand
                // (etwa „Wissensgraph mit Fehler beendet · …“ mit Kreispfeil) neben leerem Platz.
                // Ebenso bis an das Copyright, wenn die Mitte leer ist (UX 22.11.2026): erst dann kürzt er sich.
                left.add(new Capped(lead, LEAD_SHARE) {
                    @Override
                    int cap() {
                        int spare = Math.max((int) (StatusLine.this.getWidth() * LEFT_SHARE), leftRoom())
                                - (alertCap == null ? 0 : alertCap.getPreferredSize().width)
                                - westCap.getPreferredSize().width;
                        return Math.max(super.cap(), spare);
                    }
                });
            }
            left.add(westCap);
            // Zusammen höchstens LEFT_SHARE: Die Mitte (Stand der Aufträge) und das Copyright behalten ihren
            // Platz, auch wenn Hinweis, Stand des Bereichs und Stand der App je für sich voll wären; was dann
            // rechts im linken Teil nicht mehr passt (der Stand der App), fällt weg.
            add(new Capped(left, LEFT_SHARE) {
                @Override
                int cap() {
                    return Math.max(super.cap(), leftRoom());
                }
            }, BorderLayout.WEST);
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

    /**
     * Der Platz links bis an das Copyright: die Breite ohne Ränder, ohne das Copyright, ohne das, was die Mitte
     * braucht, und ohne die Abstände dazwischen. Ohne Breite (noch nicht ausgelegt) 0.
     */
    int leftRoom() {
        if (getWidth() <= 0) {
            return 0;
        }
        java.awt.Insets insets = getInsets();
        int room = getWidth() - insets.left - insets.right - GAP;
        if (east != null && east.isVisible()) {
            room -= east.getPreferredSize().width + GAP;
        }
        if (center != null && center.isVisible()) {
            room -= center.getPreferredSize().width;
        }
        return Math.max(0, room);
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

    /**
     * Mehrere Hinweise nebeneinander (Einmalcode, Proxy-Skript): reicht der Platz nicht, teilen sie ihn und kürzen
     * ihren Text mit „…“, statt in eine zweite, abgeschnittene Zeile umzubrechen; ein unsichtbarer Hinweis nimmt
     * keinen Platz ein, auch nicht seinen Abstand.
     */
    static JPanel alerts(JComponent... notices) {
        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.X_AXIS));
        row.setOpaque(false);
        for (int i = 0; i < notices.length; i++) {
            row.add(new Shrinkable(notices[i], i == 0 ? 0 : 12));
        }
        return row;
    }

    /** Ein Hinweis, der bei Platznot schmaler als gewünscht werden darf (sein Text kürzt sich dann mit „…“). */
    private static final class Shrinkable extends JPanel {
        private final JComponent child;
        private final int gap;

        Shrinkable(JComponent child, int gap) {
            super(new BorderLayout());
            this.child = child;
            this.gap = gap;
            setOpaque(false);
            add(child, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Insets getInsets() {
            return new java.awt.Insets(0, child.isVisible() ? gap : 0, 0, 0);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension size = super.getPreferredSize();
            return child.isVisible() ? size : new java.awt.Dimension(0, size.height);
        }

        @Override
        public java.awt.Dimension getMinimumSize() {
            return new java.awt.Dimension(0, super.getMinimumSize().height);
        }

        @Override
        public java.awt.Dimension getMaximumSize() {
            return getPreferredSize();
        }
    }

    /** Hält ein Kind auf höchstens einen Anteil der Breite der Statuszeile. */
    private class Capped extends JPanel {
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
            int cap = cap();
            if (cap > 0 && size.width > cap) {
                size.width = Math.min(size.width, Math.max(cap, kept(this)));
            }
            return size;
        }

        @Override
        public java.awt.Dimension getMinimumSize() {
            return new java.awt.Dimension(0, super.getMinimumSize().height);
        }

        /**
         * Was nie gekürzt wird: die Breite des Roten samt Kreispfeil jeder sichtbaren Meldung darin
         * ({@link StatusLabel#keptWidth}); ein Fehler bleibt ganz, auch wenn der Anteil kleiner wäre.
         */
        private int kept(java.awt.Container container) {
            int kept = 0;
            for (java.awt.Component child : container.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                if (child instanceof StatusLabel label) {
                    kept += label.keptWidth();
                } else if (child instanceof java.awt.Container inner) {
                    kept += kept(inner);
                }
            }
            return kept;
        }

        /** Die größte Breite in Pixeln. */
        int cap() {
            return (int) (StatusLine.this.getWidth() * share);
        }
    }

    private static Color mix(Color color, Color base, double share) {
        return new Color((int) Math.round(color.getRed() * share + base.getRed() * (1 - share)),
                (int) Math.round(color.getGreen() * share + base.getGreen() * (1 - share)),
                (int) Math.round(color.getBlue() * share + base.getBlue() * (1 - share)));
    }
}

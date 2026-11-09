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
 * sich von ganz links bis ganz rechts (auch unter „© AresStack“). Darüber links der Stand der App, mittig Schritt,
 * Abbrechen bzw. Endzustand des Auftrags, rechts das Copyright. Bei 100 % bleibt der Balken noch {@link #FADE_DELAY_MS}
 * Millisekunden stehen und verschwindet dann; der Text („Fertig“) bleibt.
 *
 * <p>Der Balken folgt dem Modell eines (nicht angezeigten) {@link JProgressBar}, den der Bereich „Aufträge“
 * fortschreibt.</p>
 */
final class StatusLine extends JPanel {

    /** So lange bleibt ein voller Balken stehen, bevor er verschwindet. */
    static final int FADE_DELAY_MS = 4000;
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
        super(new BorderLayout(12, 0));
        this.model = model;
        setOpaque(true);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 16, 4, 16)));
        add(west, BorderLayout.WEST);
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

    private static Color mix(Color color, Color base, double share) {
        return new Color((int) Math.round(color.getRed() * share + base.getRed() * (1 - share)),
                (int) Math.round(color.getGreen() * share + base.getGreen() * (1 - share)),
                (int) Math.round(color.getBlue() * share + base.getBlue() * (1 - share)));
    }
}

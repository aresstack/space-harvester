package com.aresstack.spaceharvester.app.ui;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/**
 * Der Verbindungsstand als Zeichen: grüner Haken (verbunden), rotes Ausrufezeichen (Fehler oder fehlende
 * Zugangsdaten), drehender Bogen (Prüfung läuft), grauer Ring (nicht geprüft). Der Text steht im Tooltip.
 */
final class StatusMark extends JComponent {

    enum State { UNCHECKED, CHECKING, CONNECTED, FAILED }

    private static final int SIZE = 18;

    private State state = State.UNCHECKED;
    private int angle;
    private final Timer spin = new Timer(70, e -> {
        angle = (angle + 30) % 360;
        repaint();
    });

    StatusMark() {
        setOpaque(false);
    }

    State state() {
        return state;
    }

    void state(State state, String tooltip) {
        this.state = state;
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        if (state == State.CHECKING && isShowing()) {
            spin.start();
        } else {
            spin.stop();
        }
        repaint();
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.LABEL;
                }
            };
        }
        return accessibleContext;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (state == State.CHECKING) {
            spin.start();
        }
    }

    @Override
    public void removeNotify() {
        spin.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth() - s) / 2;
            int y = (getHeight() - s) / 2;
            switch (state) {
                case CONNECTED -> {
                    g.setColor(Comic.GREEN);
                    g.fillOval(x, y, s, s);
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D tick = new Path2D.Double();
                    tick.moveTo(x + s * 0.27, y + s * 0.52);
                    tick.lineTo(x + s * 0.44, y + s * 0.69);
                    tick.lineTo(x + s * 0.74, y + s * 0.35);
                    g.draw(tick);
                }
                case FAILED -> {
                    g.setColor(Comic.RED);
                    g.fillOval(x, y, s, s);
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawLine(x + s / 2, y + (int) (s * 0.25), x + s / 2, y + (int) (s * 0.58));
                    g.fillOval(x + s / 2 - 1, y + (int) (s * 0.70), 3, 3);
                }
                case CHECKING -> {
                    g.setColor(Comic.LINE);
                    g.setStroke(new BasicStroke(2.2f));
                    g.drawOval(x + 1, y + 1, s - 2, s - 2);
                    g.setColor(Comic.BLUE);
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Arc2D.Double(x + 1, y + 1, s - 2, s - 2, -angle, 100, Arc2D.OPEN));
                }
                case UNCHECKED -> {
                    g.setColor(Comic.MUTED);
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(x + 1, y + 1, s - 2, s - 2);
                }
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE, SIZE);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }
}

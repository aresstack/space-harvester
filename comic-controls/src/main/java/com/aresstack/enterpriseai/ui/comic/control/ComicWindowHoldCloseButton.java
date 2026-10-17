package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;

/**
 * The main window's ✕ that guards against accidental closing: at rest the comic ✕, on hover it turns into a
 * minimize chip (blue, white bar) with the tooltip "Gedrückt halten zum Schließen". A short click minimizes;
 * holding the button for {@link #HOLD_MILLIS} turns the chip red, fills a ring around it and then closes.
 * Releasing after a noticeable hold but before the ring is full, or leaving the chip, cancels without
 * minimizing.
 */
public class ComicWindowHoldCloseButton extends ComicWindowCloseButton {

    public static final int HOLD_MILLIS = 800;
    static final int CLICK_MAX_MILLIS = 300;
    static final String HOLD_TOOLTIP = "Gedrückt halten zum Schließen";
    private static final int TICK_MILLIS = 16;
    private static final Runnable NOTHING = new Runnable() {
        @Override
        public void run() {
        }
    };

    private final Runnable minimizeAction;
    private final Runnable closeAction;
    private final Timer holdTimer;
    private long pressedAt;
    private float progress;
    private boolean closedByHold;

    public ComicWindowHoldCloseButton(ComicPalette palette, Runnable minimizeAction, Runnable closeAction,
                                      int size) {
        super(palette, NOTHING, HOLD_TOOLTIP, size);
        if (minimizeAction == null || closeAction == null) {
            throw new IllegalArgumentException("minimizeAction and closeAction must not be null");
        }
        this.minimizeAction = minimizeAction;
        this.closeAction = closeAction;
        getAccessibleContext().setAccessibleName("Minimieren – " + HOLD_TOOLTIP);
        holdTimer = new Timer(TICK_MILLIS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                tick();
            }
        });
        holdTimer.setRepeats(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) {
                    startHold();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                resetHold();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    resetHold();
                }
            }
        });
        // fires on a release inside the button (BasicButtonListener runs before the listener above) and on doClick
        addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onClick();
            }
        });
    }

    /** 0 … 1 while held, 0 otherwise. */
    public float holdProgress() {
        return progress;
    }

    /** Whether the chip currently shows the minimize glyph (hovered, not pressed). */
    public boolean showsMinimize() {
        return getModel().isRollover() && !getModel().isPressed();
    }

    void startHold() {
        pressedAt = System.currentTimeMillis();
        closedByHold = false;
        progress = 0f;
        holdTimer.restart();
    }

    /** Ends the hold as if the full time had elapsed (tests). */
    void completeHold() {
        pressedAt = System.currentTimeMillis() - HOLD_MILLIS;
        tick();
    }

    private void tick() {
        if (pressedAt == 0L) {
            holdTimer.stop();
            return;
        }
        long held = System.currentTimeMillis() - pressedAt;
        progress = Math.min(1f, held / (float) HOLD_MILLIS);
        repaint();
        if (held >= HOLD_MILLIS && !closedByHold) {
            closedByHold = true;
            holdTimer.stop();
            closeAction.run();
        }
    }

    private void resetHold() {
        holdTimer.stop();
        pressedAt = 0L;
        progress = 0f;
        repaint();
    }

    private void onClick() {
        if (closedByHold) {
            closedByHold = false;
            return;
        }
        long held = pressedAt == 0L ? 0L : System.currentTimeMillis() - pressedAt;
        if (held > CLICK_MAX_MILLIS) {
            return;
        }
        minimizeAction.run();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!showsMinimize() && !getModel().isPressed()) {
            super.paintComponent(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ComicPalette palette = palette();
            Ellipse2D chip = new Ellipse2D.Float(2f, 2f, getWidth() - 5f, getHeight() - 5f);
            boolean pressed = getModel().isPressed();
            g2.setColor(pressed ? palette.getAccentRed() : palette.getNavigationBlue());
            g2.fill(chip);
            g2.setColor(palette.getInk());
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(chip);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int arm = Math.max(3, getWidth() / 6);
            if (pressed) {
                g2.drawLine(cx - arm, cy - arm, cx + arm, cy + arm);
                g2.drawLine(cx + arm, cy - arm, cx - arm, cy + arm);
                if (progress > 0f) {
                    g2.setColor(palette.getAccentYellow());
                    g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(new Arc2D.Float(2f, 2f, getWidth() - 5f, getHeight() - 5f,
                            90f, -360f * progress, Arc2D.OPEN));
                }
            } else {
                g2.drawLine(cx - arm, cy, cx + arm, cy);
            }
        } finally {
            g2.dispose();
        }
    }
}

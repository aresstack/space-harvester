package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A quiet comic button: rounded surface plate with a clear ink contour; hovering fills it with
 * the accent of its role — yellow for a normal ACTION, red for a CRITICAL one (e.g. delete) —
 * and pressing deepens the fill. Text and icon painting stay with the look and feel (same font),
 * so the button is calm at rest and comic only when the user engages it.
 */
public class ComicButton extends JButton {

    /** The button's color role in the design language. */
    public enum Accent {
        /** Action/hint: yellow hover, orange press. */
        ACTION,
        /** Critical action/problem: red hover and press. */
        CRITICAL
    }

    private static final int ARC = 10;
    private static final float OUTLINE_WIDTH = 1.6f;

    private final ComicPalette palette;
    private final Accent accent;

    public ComicButton(String text) {
        this(text, null, Accent.ACTION, ComicPalette.defaultPalette());
    }

    public ComicButton(String text, Icon icon) {
        this(text, icon, Accent.ACTION, ComicPalette.defaultPalette());
    }

    public ComicButton(String text, Accent accent) {
        this(text, null, accent, ComicPalette.defaultPalette());
    }

    public ComicButton(String text, Icon icon, Accent accent, ComicPalette palette) {
        super(text, icon);
        if (accent == null || palette == null) {
            throw new IllegalArgumentException("accent and palette must not be null");
        }
        this.palette = palette;
        this.accent = accent;
        setOpaque(false);
        setContentAreaFilled(false); // the comic plate replaces the look and feel's fill
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setForeground(palette.getInk());
        setBorder(BorderFactory.createEmptyBorder(5, 14, 5, 14));
    }

    /** The button's color role (e.g. for tests and role-dependent wiring). */
    public Accent getAccent() {
        return accent;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D plate = new RoundRectangle2D.Float(
                    1f, 1f, getWidth() - 2f, getHeight() - 2f, ARC, ARC);
            g2.setColor(plateFill());
            g2.fill(plate);
            g2.setColor(isEnabled() ? palette.getInk() : palette.getInk().brighter().brighter());
            g2.setStroke(new BasicStroke(OUTLINE_WIDTH));
            g2.draw(plate);
            if (isEnabled() && isFocusOwner()) {
                paintFocusRing(g2);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g); // look-and-feel text + icon on top of the plate
    }

    /**
     * The look and feel's focus painting is off (it would draw a rectangle across the comic plate), so the
     * keyboard focus gets its own cue: a dashed navigation-blue ring just inside the ink contour.
     */
    private void paintFocusRing(Graphics2D g2) {
        g2.setColor(palette.getNavigationBlue());
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[] {3f, 2f}, 0f));
        g2.draw(new RoundRectangle2D.Float(4f, 4f, getWidth() - 8f, getHeight() - 8f, ARC - 4, ARC - 4));
    }

    private Color plateFill() {
        ButtonModel model = getModel();
        if (!isEnabled()) {
            return palette.getSurface();
        }
        if (model.isArmed() && model.isPressed()) {
            return accent == Accent.CRITICAL ? palette.getAccentRed() : palette.getAccentOrange();
        }
        if (model.isRollover()) {
            return accent == Accent.CRITICAL ? palette.getAccentRed() : palette.getAccentYellow();
        }
        return Color.WHITE; // calm resting plate, slightly lifted off the neutral surface
    }
}

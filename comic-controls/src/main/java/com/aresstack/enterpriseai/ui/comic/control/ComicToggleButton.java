package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * The toggle sibling of {@link ComicButton}: the same rounded plate with ink contour, calm when off and
 * hovered in yellow like an action. When SELECTED the plate is filled with the navigation blue and the text
 * turns white, so an active mode (e.g. "RAG an") is readable at a glance without a loud burst.
 */
public class ComicToggleButton extends JToggleButton {

    private static final int ARC = 10;
    private static final float OUTLINE_WIDTH = 1.6f;

    private final ComicPalette palette;

    public ComicToggleButton(String text) {
        this(text, ComicPalette.defaultPalette());
    }

    public ComicToggleButton(String text, ComicPalette palette) {
        super(text);
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder(5, 14, 5, 14));
        setForeground(palette.getInk());
        addItemListener(event -> setForeground(isSelected() ? Color.WHITE : this.palette.getInk()));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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
        super.paintComponent(g);
    }

    /**
     * The look and feel's focus painting is off (it would draw a rectangle across the comic plate), so the
     * keyboard focus gets its own cue: a dashed ring just inside the ink contour (blue, white on the selected blue plate).
     */
    private void paintFocusRing(Graphics2D g2) {
        g2.setColor(isSelected() ? Color.WHITE : palette.getNavigationBlue()); // stays visible on the blue plate
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                new float[] {3f, 2f}, 0f));
        g2.draw(new RoundRectangle2D.Float(4f, 4f, getWidth() - 8f, getHeight() - 8f, ARC - 4, ARC - 4));
    }

    private Color plateFill() {
        ButtonModel model = getModel();
        if (!isEnabled()) {
            return palette.getSurface();
        }
        if (model.isSelected()) {
            return model.isRollover() ? palette.getNavigationBlue().darker() : palette.getNavigationBlue();
        }
        if (model.isArmed() && model.isPressed()) {
            return palette.getAccentOrange();
        }
        if (model.isRollover()) {
            return palette.getAccentYellow();
        }
        return Color.WHITE;
    }
}

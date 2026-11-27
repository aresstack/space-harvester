package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** Der gefüllte Punkt einer farbigen Gruppe (UX: 10 px) vor einem Namen; die Farbe richtet sich nach dem Grund. */
public final class ColorDot implements Icon {

    /** Kantenlänge nach UX-Vorgabe. */
    public static final int SIZE = 10;

    private final GroupColors color;
    private final int size;

    public ColorDot(GroupColors color) {
        this(color, SIZE);
    }

    public ColorDot(GroupColors color, int size) {
        this.color = color;
        this.size = size;
    }

    public GroupColors color() {
        return color;
    }

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color background = c == null ? null : c.getBackground();
            g.setColor(color.on(background));
            g.fillOval(x, y, size, size);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}

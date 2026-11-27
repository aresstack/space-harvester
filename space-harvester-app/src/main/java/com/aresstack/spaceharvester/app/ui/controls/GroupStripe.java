package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * Der durchgehende Streifen links an den Einträgen einer farbigen Gruppe (UX: 3 px, wie die Gruppenlinie in Firefox):
 * Untereinander stehende Einträge mit diesem Rand ergeben eine Linie über die ganze Gruppe. Rückt den Inhalt ein.
 */
public final class GroupStripe extends AbstractBorder {

    /** Breite des Streifens. */
    public static final int WIDTH = 3;

    private final GroupColors color;
    private final int indent;

    /**
     * @param color  die Farbe der Gruppe
     * @param indent Abstand des Streifens vom linken Rand, der Inhalt beginnt danach mit {@code indent} Abstand
     */
    public GroupStripe(GroupColors color, int indent) {
        this.color = color;
        this.indent = indent;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        g.setColor(color.on(c.getBackground()));
        g.fillRect(x + indent, y, WIDTH, height);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.set(0, indent + WIDTH + indent, 0, 0);
        return insets;
    }
}

package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Ein runder Knopf nur mit Symbol; der Text steht im Tooltip und als zugänglicher Name (Bildschirmleser, Tastatur:
 * Tab und Leertaste). Durchsichtig in Ruhe, beim Darüberfahren der Kreis aus {@link RoundHover}.
 */
public final class IconButton extends JButton {

    private static final int SIZE = RoundHover.SIZE;
    /** Kantenlänge in engen Zeilen wie der Statuszeile ({@link #compact()}). */
    static final int COMPACT_SIZE = 22;
    private int size = SIZE;

    public IconButton(Icon icon, String tooltip) {
        super(icon);
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(true);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(Comic.INK);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed() || hasFocus())) {
            RoundHover.paint(graphics, 0, 0, getWidth(), getHeight(), getModel().isPressed());
        }
        super.paintComponent(graphics);
    }

    /** Kleiner Knopf für enge Zeilen (Statuszeile): Die Zeile behält ihre Höhe. */
    public IconButton compact() {
        size = COMPACT_SIZE;
        setMargin(new java.awt.Insets(0, 0, 0, 0));
        revalidate();
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(size, size);
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

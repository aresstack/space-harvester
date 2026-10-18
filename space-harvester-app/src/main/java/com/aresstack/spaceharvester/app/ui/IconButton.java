package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Ein runder Knopf nur mit Symbol; der Text steht im Tooltip und als zugänglicher Name (Bildschirmleser, Tastatur:
 * Tab und Leertaste). Durchsichtig in Ruhe, beim Darüberfahren der Kreis aus {@link RoundHover}.
 */
final class IconButton extends JButton {

    private static final int SIZE = RoundHover.SIZE;

    IconButton(Icon icon, String tooltip) {
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

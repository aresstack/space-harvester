package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Ein runder Knopf nur mit Symbol; der Text steht im Tooltip und als zugänglicher Name (Bildschirmleser, Tastatur:
 * Tab und Leertaste). Durchsichtig in Ruhe, beim Darüberfahren eine helle Fläche.
 */
final class IconButton extends JButton {

    private static final int SIZE = 26;

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
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getModel().isPressed() ? Comic.LINE : new java.awt.Color(255, 255, 255, 170));
                g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(Comic.INK);
                g.drawOval(0, 0, getWidth() - 1, getHeight() - 1);
            } finally {
                g.dispose();
            }
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

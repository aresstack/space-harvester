package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/**
 * Eine Aktionspille mit kurzem Text (etwa „Seite zusammenfassen“ in der unteren Leiste des Chats): dieselbe Form wie
 * {@link CountChip}, aber ein Knopf, kein Filter. Beim Überfahren gelb umrandet; gesperrt blass.
 */
public final class ActionChip extends JButton {

    private static final int HEIGHT = 24;
    private static final int PAD = 10;
    private final KeyboardFocusRing focusRing;

    public ActionChip(String text, String tooltip) {
        super(Objects.requireNonNull(text, "text"));
        setFont(Comic.semiBold(12f));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder());
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(text);
        focusRing = KeyboardFocusRing.install(this);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        return new Dimension(metrics.stringWidth(getText()) + 2 * PAD, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            boolean hot = isEnabled() && (getModel().isRollover() || focusRing.shown());
            boolean pressed = isEnabled() && getModel().isArmed() && getModel().isPressed();
            g.setColor(pressed ? Comic.YELLOW : hot ? Comic.NOTICE : Comic.SURFACE);
            g.fillRoundRect(0, 0, w, h, h, h);
            g.setColor(!isEnabled() ? Comic.LINE : hot ? Comic.YELLOW : Comic.INK);
            g.drawRoundRect(0, 0, w, h, h, h);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(isEnabled() ? Comic.INK : Comic.MUTED);
            int x = (getWidth() - metrics.stringWidth(getText())) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(getText(), x, y);
        } finally {
            g.dispose();
        }
    }
}

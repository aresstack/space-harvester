package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A 34×34 icon-only button of the research-UI design study (the chats-footer gear): transparent at
 * rest, {@link ResearchUiPalette#SECONDARY_SURFACE} on hover, muted glyph that brightens on hover.
 * The icon paints with the button's CURRENT foreground, so a foreground-aware icon follows along.
 */
public final class ResearchIconButton extends JButton {

    private static final int SIZE = ResearchUiMetrics.FOOTER_ICON_BUTTON;

    public ResearchIconButton(Icon icon, String tooltip) {
        super(icon);
        setToolTipText(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(ResearchUiPalette.TEXT_MUTED);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        boolean hovered = getModel().isRollover() || getModel().isPressed();
        setForeground(hovered ? ResearchUiPalette.TEXT_PRIMARY : ResearchUiPalette.TEXT_MUTED);
        if (hovered) {
            Graphics2D g2 = ResearchUiPainter.prepare(graphics);
            try {
                ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(),
                        ResearchUiMetrics.RADIUS_CONTROL, ResearchUiPalette.SECONDARY_SURFACE);
            } finally {
                g2.dispose();
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

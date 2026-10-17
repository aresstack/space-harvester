package com.aresstack.enterpriseai.ui.comic.paint;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The research-UI check box glyph (16×16): a rounded white box with a light border at rest, filled
 * {@link ResearchUiPalette#ACCENT_BLUE} with a white tick when selected, muted when the button is
 * disabled. Set it as a {@code JCheckBox}'s icon; selection and enablement come from the button model.
 */
public final class ResearchCheckIcon implements Icon {

    private static final int SIZE = 16;

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        boolean selected = false;
        boolean enabled = component == null || component.isEnabled();
        boolean hovered = false;
        if (component instanceof AbstractButton) {
            AbstractButton button = (AbstractButton) component;
            selected = button.isSelected();
            hovered = button.getModel().isRollover();
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = selected ? (enabled ? ResearchUiPalette.ACCENT_BLUE : ResearchUiPalette.LIGHT_TEXT_MUTED)
                    : ResearchUiPalette.LIGHT_CONTROL_BG;
            g2.setColor(fill);
            g2.fillRoundRect(1, 1, SIZE - 2, SIZE - 2, 6, 6);
            if (!selected) {
                g2.setColor(hovered && enabled ? ResearchUiPalette.ACCENT_BLUE
                        : ResearchUiPainter.mix(ResearchUiPalette.LIGHT_CONTROL_BORDER, Color.BLACK, 0.1f));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawRoundRect(1, 1, SIZE - 3, SIZE - 3, 6, 6);
            } else {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawPolyline(new int[]{4, 7, 12}, new int[]{8, 11, 5}, 3);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}

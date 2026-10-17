package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.AbstractButton;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * The ONE painter behind {@link ComposerButton} and {@link ComposerToggleButton} (AskAI's composer
 * button family): a filled rounded accent plate for primary/emphasized buttons (brighter on hover,
 * darker pressed), a translucent hover wash for flat buttons, muted glyph/text when disabled.
 */
final class ComposerButtonStyle {

    static final Color DISABLED_FILL = new Color(0xB7BBC1);
    static final Color DISABLED_TEXT = new Color(0xA5A9AE);
    static final Color HOVER_WASH = new Color(0, 0, 0, 18);
    static final int FILLED_ARC = 14;
    static final int HOVER_ARC = 12;
    static final int MIN_HEIGHT = 28;
    static final int MIN_WIDTH_PRIMARY = 72;
    static final int MIN_WIDTH_FLAT = 30;

    private ComposerButtonStyle() {
    }

    /**
     * No LaF chrome, hand cursor, the family's font and margins. Reachable with Tab (Space activates, the ring
     * below marks the focus), but a mouse click never takes the focus away from the editor.
     */
    static void configure(AbstractButton button, String tooltip) {
        button.setHorizontalTextPosition(AbstractButton.RIGHT);
        button.setIconTextGap(5);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11.5f));
        button.setFocusable(true);
        button.setRequestFocusEnabled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(4, 8, 4, 8));
        if (tooltip != null) {
            button.setToolTipText(tooltip);
            button.getAccessibleContext().setAccessibleName(tooltip);
        }
    }

    /** Paint plate/wash and set the foreground the glyph and text will use. */
    static void paintBackground(AbstractButton button, Graphics graphics, Color accent, boolean filled) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color active = accent == null ? ResearchUiPalette.ACCENT_BLUE : accent;
            boolean enabled = button.isEnabled();
            boolean hovered = button.getModel().isRollover();
            boolean pressed = button.getModel().isPressed();
            if (filled) {
                Color fill = enabled ? active : DISABLED_FILL;
                if (pressed && enabled) {
                    fill = fill.darker();
                } else if (hovered && enabled) {
                    fill = fill.brighter();
                }
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), FILLED_ARC, FILLED_ARC);
                button.setForeground(Color.WHITE);
            } else if (!enabled) {
                button.setForeground(DISABLED_TEXT);
            } else if (hovered) {
                g2.setColor(HOVER_WASH);
                g2.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), HOVER_ARC, HOVER_ARC);
                button.setForeground(flatForeground());
            } else {
                button.setForeground(flatForeground());
            }
            if (button.isFocusOwner()) {
                paintFocusRing(g2, button, filled && enabled ? Color.WHITE : active, filled ? FILLED_ARC : HOVER_ARC);
            }
        } finally {
            g2.dispose();
        }
    }

    /** The keyboard focus: a thin ring just inside the plate (white on a filled plate, accent on a flat one). */
    private static void paintFocusRing(Graphics2D g2, AbstractButton button, Color color, int arc) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(1, 1, button.getWidth() - 3, button.getHeight() - 3, arc, arc);
    }

    static Color flatForeground() {
        Color foreground = UIManager.getColor("Button.foreground");
        return foreground == null ? ResearchUiPalette.LIGHT_CONTROL_TEXT : foreground;
    }

    static Dimension preferredSize(Dimension natural, boolean primary) {
        int height = Math.max(MIN_HEIGHT, natural.height);
        return new Dimension(Math.max(primary ? MIN_WIDTH_PRIMARY : MIN_WIDTH_FLAT, natural.width), height);
    }
}

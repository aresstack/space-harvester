package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A rounded dark button of the research-UI design study (e.g. "+ Neuer Chat", "+ Hinzufügen"):
 * fixed height, configurable radius/padding, three explicit fills (normal/hover/pressed) from
 * {@link ResearchUiPalette}. Only as wide as its text needs — never stretched by the caller.
 */
public class ResearchPillButton extends JButton {

    private final int fixedHeight;
    private final int radius;
    private final int paddingH;
    private Color normalFill = ResearchUiPalette.SECONDARY_SURFACE;
    private Color hoverFill = ResearchUiPalette.SECONDARY_HOVER;
    private Color pressedFill = ResearchUiPalette.ACCENT_BLUE;
    private Color normalForeground = ResearchUiPalette.TEXT_PRIMARY;
    private Color hoverForeground = ResearchUiPalette.TEXT_PRIMARY;
    private Color pressedForeground = Color.WHITE;
    private Color normalBorder;
    private Color hoverBorder;
    private Color pressedBorder;
    private String widthReference;

    public ResearchPillButton(String text, int fixedHeight, int radius, int paddingH) {
        super(text);
        this.fixedHeight = fixedHeight;
        this.radius = radius;
        this.paddingH = paddingH;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(normalForeground);
    }

    public void setFills(Color normal, Color hover, Color pressed) {
        this.normalFill = normal;
        this.hoverFill = hover;
        this.pressedFill = pressed;
        repaint();
    }

    public void setForegrounds(Color normal, Color hover, Color pressed) {
        this.normalForeground = normal;
        this.hoverForeground = hover;
        this.pressedForeground = pressed;
        setForeground(normal);
        repaint();
    }

    /** Optional 1px borders per state ({@code null} = borderless, the default). */
    public void setBorders(Color normal, Color hover, Color pressed) {
        this.normalBorder = normal;
        this.hoverBorder = hover;
        this.pressedBorder = pressed;
        repaint();
    }

    /**
     * Size the button to at least the width this reference text needs, so pills with different labels
     * line up with the same width ({@code null} = only as wide as the own text, the default).
     */
    public void setWidthReference(String reference) {
        this.widthReference = reference;
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            boolean pressed = getModel().isArmed() && getModel().isPressed();
            boolean hovered = getModel().isRollover();
            Color fill = pressed ? pressedFill : hovered ? hoverFill : normalFill;
            ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius, fill);
            Color border = pressed ? pressedBorder : hovered ? hoverBorder : normalBorder;
            if (border != null) {
                ResearchUiPainter.strokeRound(g2, 0, 0, getWidth(), getHeight(), radius, border);
            }
            g2.setColor(pressed ? pressedForeground : hovered ? hoverForeground : normalForeground);
            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(getText(), paddingH, textY);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int textWidth = metrics.stringWidth(getText());
        if (widthReference != null) {
            textWidth = Math.max(textWidth, metrics.stringWidth(widthReference));
        }
        int width = textWidth + 2 * paddingH;
        return new Dimension(width, fixedHeight);
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

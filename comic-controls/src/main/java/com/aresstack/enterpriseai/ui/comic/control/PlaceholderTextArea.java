package com.aresstack.enterpriseai.ui.comic.control;

import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * A transparent, word-wrapping text area that paints a muted placeholder while it is empty — the composer
 * editor of the AskAI chat, extracted so any comic surface can host it.
 */
public class PlaceholderTextArea extends JTextArea {

    /** Muted, but readable on the white composer plate (about 5:1, WCAG AA for normal text). */
    private static final Color PLACEHOLDER = new Color(0x6B6F76);

    private String placeholder;

    public PlaceholderTextArea(String placeholder, int rows, int columns) {
        super(rows, columns);
        this.placeholder = placeholder;
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(new EmptyBorder(3, 4, 3, 4));
        setFont(getFont().deriveFont(13f));
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getText().length() != 0 || placeholder == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(PLACEHOLDER);
            g2.setFont(getFont().deriveFont(Font.PLAIN));
            FontMetrics metrics = g2.getFontMetrics();
            Insets insets = getInsets();
            g2.drawString(placeholder, insets.left, insets.top + metrics.getAscent());
        } finally {
            g2.dispose();
        }
    }
}

package com.aresstack.spaceharvester.app.ui;

import javax.swing.JTextField;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/** Textfeld mit grauem Platzhalter, solange es leer ist; der Platzhalter ist nur Anzeige, nicht der Text. */
public final class PlaceholderField extends JTextField {

    private final String placeholder;

    public PlaceholderField(int columns, String placeholder) {
        super(columns);
        this.placeholder = placeholder;
        Comic.style(this);
    }

    String placeholder() {
        return placeholder;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!getText().isEmpty() || placeholder == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(getFont());
        g2.setColor(Comic.MUTED);
        Insets insets = getInsets();
        FontMetrics metrics = g2.getFontMetrics();
        int y = insets.top + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(placeholder, insets.left, y);
        g2.dispose();
    }
}

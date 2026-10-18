package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Symbol eines Ausgabeformats: ein Blatt mit Eselsohr und darauf das Kürzel in der Farbe des Formats (PDF rot, Word
 * blau, Markdown Tinte, JSON Petrol), damit die Formate in der Auftragsliste ohne Text zu unterscheiden sind.
 */
final class FormatIcon implements Icon {

    private static final int WIDTH = 18;
    private static final int HEIGHT = 20;

    private final String label;
    private final Color color;

    private FormatIcon(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    /** Das Symbol zu einem Format-Kürzel des Core ({@code pdf}, {@code docx}, {@code markdown}, {@code json}). */
    static FormatIcon of(String format) {
        return switch (format) {
            case "pdf" -> new FormatIcon("PDF", Comic.RED);
            case "docx" -> new FormatIcon("W", Comic.BLUE);
            case "markdown" -> new FormatIcon("MD", Comic.INK);
            case "json" -> new FormatIcon("{}", Comic.PETROL);
            default -> new FormatIcon(format.substring(0, Math.min(3, format.length())).toUpperCase(
                    java.util.Locale.ROOT), Comic.INK);
        };
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.translate(x, y);
            Path2D.Float sheet = new Path2D.Float();
            sheet.moveTo(2.5f, 0.5f);
            sheet.lineTo(11.5f, 0.5f);
            sheet.lineTo(15.5f, 4.5f);
            sheet.lineTo(15.5f, 19.5f);
            sheet.lineTo(2.5f, 19.5f);
            sheet.closePath();
            g.setColor(Color.WHITE);
            g.fill(sheet);
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(Comic.INK);
            g.draw(sheet);
            g.drawLine(11, 1, 11, 5);
            g.drawLine(11, 5, 15, 5);
            g.setColor(color);
            float size = label.length() > 2 ? 6.5f : 8f;
            g.setFont(Comic.semiBold(size).deriveFont(Font.BOLD));
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(label);
            g.drawString(label, (WIDTH - width) / 2f - 0.5f, 15f);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }
}

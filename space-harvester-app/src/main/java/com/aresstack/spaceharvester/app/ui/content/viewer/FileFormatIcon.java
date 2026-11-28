package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Farbiges Dateisymbol für die Knöpfe „In PPTX umwandeln“ und „In PDF umwandeln“ am Video (Angelo 20.11.2026): ein
 * weißes Blatt mit Eselsohr, davor ein farbiges Schild mit dem Kürzel des Formats (orangerot „P“ für PowerPoint, rot
 * „PDF“). Gezeichnet statt geladen, damit es in jeder Größe scharf bleibt; gesperrt erscheint es grau.
 */
final class FileFormatIcon implements Icon {

    private static final int WIDTH = 24;
    private static final int HEIGHT = 19;
    private static final Color SHEET = AppColors.of("app.FileFormatIcon.sheet");
    private static final Color FOLD = AppColors.of("app.FileFormatIcon.fold");
    private static final Color DISABLED = AppColors.of("app.FileFormatIcon.disabled");

    private final String label;
    private final Color color;

    private FileFormatIcon(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    /** PowerPoint: orangerotes Schild mit „P“. */
    static FileFormatIcon powerPoint() {
        return new FileFormatIcon("P", AppColors.of("app.FileFormatIcon.powerPoint"));
    }

    /** PDF: rotes Schild mit „PDF“. */
    static FileFormatIcon pdf() {
        return new FileFormatIcon("PDF", AppColors.of("app.FileFormatIcon.pdf"));
    }

    /** Die Farbe des Schilds, für Tests. */
    Color color() {
        return color;
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D paint = (Graphics2D) graphics.create();
        try {
            paint.translate(x, y);
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paint.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean enabled = component == null || component.isEnabled();
            // Das Blatt, rechts oben das Eselsohr.
            Path2D sheet = new Path2D.Double();
            sheet.moveTo(9, 0.5);
            sheet.lineTo(18, 0.5);
            sheet.lineTo(23.5, 6);
            sheet.lineTo(23.5, 18.5);
            sheet.lineTo(9, 18.5);
            sheet.closePath();
            paint.setColor(enabled ? SHEET : AppColors.of("app.FileFormatIcon.paintIcon"));
            paint.fill(sheet);
            Path2D fold = new Path2D.Double();
            fold.moveTo(18, 0.5);
            fold.lineTo(18, 6);
            fold.lineTo(23.5, 6);
            fold.closePath();
            paint.setColor(enabled ? FOLD : DISABLED);
            paint.fill(fold);
            // Das Schild mit dem Kürzel, links über den Rand des Blatts hinaus.
            paint.setColor(enabled ? color : DISABLED);
            paint.fill(new RoundRectangle2D.Double(0, 6.5, 19, 10, 3, 3));
            paint.setColor(AppColors.of("app.FileFormatIcon.paintIcon2"));
            float size = label.length() > 1 ? 7f : 9.5f;
            paint.setFont(paint.getFont().deriveFont(Font.BOLD, size));
            FontMetrics metrics = paint.getFontMetrics();
            float textX = (19 - metrics.stringWidth(label)) / 2f;
            float textY = 6.5f + (10 - metrics.getHeight()) / 2f + metrics.getAscent();
            paint.drawString(label, textX, textY);
        } finally {
            paint.dispose();
        }
    }
}

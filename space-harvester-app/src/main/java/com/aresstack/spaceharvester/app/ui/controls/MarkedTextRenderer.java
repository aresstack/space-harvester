package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Tabellenzelle mit einer klickbaren Marke vorn (gedämpfte Pille) und dem Text dahinter, etwa der Treffergrund vor dem
 * Auszug eines Suchtreffers. Wird die Zelle schmal, zeigt die Pille nur ihre Kurzform, damit der Text nicht verdrängt
 * wird. {@link #markBounds} liefert dieselbe Fläche, die gezeichnet wird, für Klick und Hand-Cursor.
 */
public final class MarkedTextRenderer extends DefaultTableCellRenderer {

    /** Eine Marke in Lang- und Kurzform. */
    public record Mark(String full, String brief) {

        public Mark {
            Objects.requireNonNull(full, "full");
            brief = brief == null || brief.isBlank() ? full : brief;
        }
    }

    private static final int LEFT = 4;
    private static final int PAD = 7;
    private static final int GAP = 6;
    private static final int HEIGHT = 18;
    /** So viel Platz bleibt dem Text mindestens, sonst wird die Marke kurz. */
    private static final int TEXT_ROOM = 220;
    private static final Color FILL = AppColors.of("app.MarkedTextRenderer.fill");

    private final IntFunction<Mark> marks;
    private String painted;

    /** @param marks die Marke zur Zeile des Modells oder {@code null} ohne Marke */
    public MarkedTextRenderer(IntFunction<Mark> marks) {
        this.marks = Objects.requireNonNull(marks, "marks");
        putClientProperty("html.disable", Boolean.TRUE);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                   int row, int column) {
        super.getTableCellRendererComponent(table, value, selected, focus, row, column);
        Mark mark = marks.apply(table.convertRowIndexToModel(row));
        int width = table.getColumnModel().getColumn(column).getWidth();
        painted = mark == null ? null : text(mark, width, getFont(table));
        int indent = painted == null ? 1 : LEFT + pillWidth(painted, getFont(table)) + GAP;
        setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 1));
        return this;
    }

    /** Die Fläche der Marke in Koordinaten der Tabelle, leer ohne Marke. */
    public Rectangle markBounds(JTable table, int row, int column) {
        Mark mark = marks.apply(table.convertRowIndexToModel(row));
        if (mark == null) {
            return new Rectangle();
        }
        Rectangle cell = table.getCellRect(row, column, false);
        String text = text(mark, cell.width, getFont(table));
        return new Rectangle(cell.x + LEFT, cell.y + (cell.height - HEIGHT) / 2, pillWidth(text, getFont(table)),
                HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (painted == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = markFont(getFont());
        int width = pillWidth(painted, getFont());
        int y = (getHeight() - HEIGHT) / 2;
        g2.setColor(FILL);
        g2.fillRoundRect(LEFT, y, width, HEIGHT, HEIGHT, HEIGHT);
        g2.setColor(Comic.LINE);
        g2.drawRoundRect(LEFT, y, width - 1, HEIGHT - 1, HEIGHT, HEIGHT);
        g2.setFont(font);
        g2.setColor(Comic.MUTED);
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString(painted, LEFT + PAD, y + (HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent());
        g2.dispose();
    }

    private Font getFont(JTable table) {
        return table.getFont();
    }

    private String text(Mark mark, int cellWidth, Font font) {
        return LEFT + pillWidth(mark.full(), font) + GAP + TEXT_ROOM <= cellWidth ? mark.full() : mark.brief();
    }

    private int pillWidth(String text, Font font) {
        return getFontMetrics(markFont(font)).stringWidth(text) + 2 * PAD;
    }

    private static Font markFont(Font base) {
        return Comic.semiBold(Math.max(10f, base.getSize2D() - 1.5f));
    }
}

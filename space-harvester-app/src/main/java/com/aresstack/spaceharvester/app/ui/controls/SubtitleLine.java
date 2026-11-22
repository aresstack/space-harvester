package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.Icon;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Der Untertitel einer Wiedergabe (Angelo 22.11.2026): der gerade gesprochene Satz, fett, mittig, in höchstens
 * {@link #LINES} Zeilen. Ein längerer Satz wird an Wortgrenzen in Seiten zu je {@link #LINES} Zeilen geteilt; gezeigt
 * wird die Seite, die zum Fortschritt im Satz gehört. Die Höhe bleibt immer gleich, damit darunter nichts springt.
 */
public final class SubtitleLine extends JPanel {

    /** So viele Zeilen stehen höchstens zugleich da. */
    public static final int LINES = 2;
    private static final Font FONT = Comic.semiBold(14f);

    private String text = "";
    private double share;

    public SubtitleLine() {
        setOpaque(false);
        setFont(FONT);
        getAccessibleContext().setAccessibleName("Untertitel");
    }

    /**
     * Zeigt {@code sentence}; {@code share} (0 bis 1) sagt, wie weit der Satz gesprochen ist, und wählt bei einem
     * langen Satz die Seite. Leer nimmt den Untertitel weg.
     */
    public void show(String sentence, double share) {
        String next = sentence == null ? "" : sentence.strip();
        double within = Math.max(0, Math.min(1, share));
        if (next.equals(text) && page(pages(), within) == page(pages(), this.share)) {
            this.share = within;
            return;
        }
        text = next;
        this.share = within;
        getAccessibleContext().setAccessibleDescription(next);
        repaint();
    }

    public String text() {
        return text;
    }

    /** Die Zeilen, die gerade zu sehen sind (für Tests). */
    public List<String> shownLines() {
        List<List<String>> pages = pages();
        return pages.isEmpty() ? List.of() : pages.get(page(pages, share));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, LINES * getFontMetrics(FONT).getHeight());
    }

    private List<List<String>> pages() {
        return pages(text, getFontMetrics(FONT), getWidth());
    }

    /** Bricht {@code text} an Wortgrenzen auf {@code width} um und fasst je {@link #LINES} Zeilen zu einer Seite. */
    static List<List<String>> pages(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.isBlank() ? new String[0] : text.strip().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width > 0 && metrics.stringWidth(candidate) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += LINES) {
            pages.add(List.copyOf(lines.subList(i, Math.min(lines.size(), i + LINES))));
        }
        return pages;
    }

    /** Die Seite, auf der die Stelle {@code share} des Satzes steht; gewichtet nach der Länge der Seiten. */
    static int page(List<List<String>> pages, double share) {
        int total = 0;
        for (List<String> page : pages) {
            total += length(page);
        }
        int at = 0;
        for (int i = 0; i < pages.size(); i++) {
            at += length(pages.get(i));
            if (share * total < at) {
                return i;
            }
        }
        return Math.max(0, pages.size() - 1);
    }

    private static int length(List<String> page) {
        int length = 0;
        for (String line : page) {
            length += line.length() + 1;
        }
        return length;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        List<String> lines = shownLines();
        if (lines.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(FONT);
            g.setColor(Comic.INK);
            FontMetrics metrics = g.getFontMetrics();
            // Eine einzelne Zeile steht oben, auf der Höhe der Zeitangaben; die zweite füllt den Platz darunter.
            int y = metrics.getAscent();
            for (String line : lines) {
                g.drawString(line, Math.max(0, (getWidth() - metrics.stringWidth(line)) / 2), y);
                y += metrics.getHeight();
            }
        } finally {
            g.dispose();
        }
    }

    /** Das Symbol des Schalters „Untertitel“: ein Bildrahmen mit zwei Textzeilen; aus = durchgestrichen. */
    public static Icon icon(boolean on) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    g.translate(x, y);
                    g.setColor(c != null && !c.isEnabled() ? Comic.MUTED : Comic.INK);
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new RoundRectangle2D.Double(1.2, 2.8, 13.6, 10.4, 3, 3));
                    g.draw(new Line2D.Double(4, 7.2, 7, 7.2));
                    g.draw(new Line2D.Double(9, 7.2, 12, 7.2));
                    g.draw(new Line2D.Double(4, 10, 9.5, 10));
                    if (!on) {
                        g.draw(new Line2D.Double(2, 14.4, 14, 1.6));
                    }
                } finally {
                    g.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }
}

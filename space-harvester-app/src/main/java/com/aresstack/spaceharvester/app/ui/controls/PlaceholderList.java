package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JList;
import javax.swing.ListModel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Eine Liste, die leer einen gedämpften Satz in ihrer Fläche zeigt (etwa „Keine Treffer“), statt einfach weiß zu
 * bleiben. Der Satz steht oben links, in der Schrift und Farbe der gedämpften Hinweise ({@link Comic#muted}), und
 * bricht um, wenn die Fläche schmaler ist als er.
 */
public class PlaceholderList<T> extends JList<T> {

    private static final int INSET = 12;

    private String placeholder = "";

    public PlaceholderList(ListModel<T> model) {
        super(model);
        getModel().addListDataListener(new javax.swing.event.ListDataListener() {
            @Override
            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                repaint();
            }

            @Override
            public void intervalRemoved(javax.swing.event.ListDataEvent e) {
                repaint();
            }

            @Override
            public void contentsChanged(javax.swing.event.ListDataEvent e) {
                repaint();
            }
        });
    }

    /** Der Satz für die leere Liste; {@code null} oder leer zeigt nichts. */
    public void placeholder(String text) {
        String next = text == null ? "" : text;
        if (!next.equals(placeholder)) {
            placeholder = next;
            getAccessibleContext().setAccessibleDescription(next.isEmpty() ? null : next);
            repaint();
        }
    }

    /** Der Satz, den die leere Liste zeigt (auch wenn sie gerade Zeilen hat). */
    public String placeholder() {
        return placeholder;
    }

    /** Ob die Liste gerade den Satz zeigt. */
    public boolean showsPlaceholder() {
        return getModel().getSize() == 0 && !placeholder.isEmpty();
    }

    /**
     * Bricht den Satz an Leerzeichen um, damit er in schmalen Flächen ganz lesbar bleibt statt abgeschnitten zu
     * werden. Ein einzelnes Wort, das breiter ist als die Fläche, bleibt ungeteilt in seiner Zeile.
     */
    static java.util.List<String> lines(String text, java.util.function.ToIntFunction<String> width, int available) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String longer = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width.applyAsInt(longer) > available) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(longer);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!showsPlaceholder()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(Comic.regular(13f));
            g2.setColor(Comic.MUTED);
            java.awt.FontMetrics metrics = g2.getFontMetrics();
            int y = INSET + metrics.getAscent();
            for (String line : lines(placeholder, metrics::stringWidth, getVisibleRect().width - 2 * INSET)) {
                g2.drawString(line, INSET, y);
                y += metrics.getHeight();
            }
        } finally {
            g2.dispose();
        }
    }
}

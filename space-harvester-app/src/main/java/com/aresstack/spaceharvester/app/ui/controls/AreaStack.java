package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

/**
 * Die Bereiche einer Seitenleiste untereinander (Angelo 28.11.2026): Ein offener Bereich bekommt die ganze Höhe, zwei
 * teilen sie sich je zur Hälfte, drei zu Dritteln. Zwischen zwei Bereichen liegt die Linie der Trenner, ohne Griff:
 * Die Höhen sind immer gleich und lassen sich nicht ziehen. Bereiche, die gerade nicht gezeigt werden, bleiben
 * unsichtbar in der Fläche.
 */
public final class AreaStack extends JPanel {

    /** Abstand zwischen zwei Bereichen, so dick wie ein Trenner. */
    public static final int GAP = ComicDivider.THICKNESS;
    /** Die Linie endet wie bei einem Trenner kurz vor den Rändern. */
    private static final int LINE_INSET = 8;
    private static final int LINE_WIDTH = 2;

    private final List<Component> shown = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    /** Die zuletzt gestellten Rechtecke, um nur echte Änderungen zu melden. */
    private List<java.awt.Rectangle> placed = List.of();

    public AreaStack() {
        super(null);
        setLayout(new Shares());
        setBackground(Comic.SURFACE);
    }

    /** Zeigt genau diese Bereiche, in dieser Reihenfolge von oben nach unten; alle anderen verschwinden. */
    public void show(List<? extends Component> areas) {
        shown.clear();
        shown.addAll(areas);
        for (Component area : areas) {
            if (area.getParent() != this) {
                add(area);
            }
        }
        for (Component child : getComponents()) {
            child.setVisible(shown.contains(child));
        }
        revalidate();
        repaint();
    }

    /** Meldet, wenn die gezeigten Bereiche einen neuen Platz bekommen haben (wer auf ihrer Höhe sitzt, zieht nach). */
    public void onLayout(Runnable listener) {
        listeners.add(listener);
    }

    /** Die gezeigten Bereiche von oben nach unten. */
    public List<Component> shown() {
        return List.copyOf(shown);
    }

    @Override
    public void remove(Component component) {
        shown.remove(component);
        super.remove(component);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Comic.INK);
            for (int index = 1; index < shown.size(); index++) {
                int y = shown.get(index).getY() - GAP / 2 - LINE_WIDTH / 2;
                g.fillRoundRect(LINE_INSET, y, Math.max(LINE_WIDTH, getWidth() - 2 * LINE_INSET), LINE_WIDTH,
                        LINE_WIDTH, LINE_WIDTH);
            }
        } finally {
            g.dispose();
        }
    }

    /** Gleiche Höhen für alle gezeigten Bereiche. */
    private final class Shares implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return size(true);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return size(false);
        }

        private Dimension size(boolean preferred) {
            int width = 0;
            int tallest = 0;
            for (Component area : shown) {
                Dimension size = preferred ? area.getPreferredSize() : area.getMinimumSize();
                width = Math.max(width, size.width);
                tallest = Math.max(tallest, size.height);
            }
            Insets insets = getInsets();
            int count = shown.size();
            return new Dimension(width + insets.left + insets.right,
                    tallest * count + GAP * Math.max(0, count - 1) + insets.top + insets.bottom);
        }

        @Override
        public void layoutContainer(Container parent) {
            int count = shown.size();
            Insets insets = getInsets();
            int width = getWidth() - insets.left - insets.right;
            int room = getHeight() - insets.top - insets.bottom - GAP * (count - 1);
            int y = insets.top;
            for (int index = 0; index < count; index++) {
                // Der Rest der Teilung geht an die oberen Bereiche, damit unten nichts übersteht.
                int height = room / count + (index < room % count ? 1 : 0);
                shown.get(index).setBounds(insets.left, y, width, Math.max(0, height));
                y += height + GAP;
            }
            List<java.awt.Rectangle> now = new ArrayList<>();
            for (Component area : shown) {
                now.add(area.getBounds());
            }
            if (!now.equals(placed)) {
                placed = now;
                for (Runnable listener : List.copyOf(listeners)) {
                    listener.run();
                }
            }
        }
    }
}

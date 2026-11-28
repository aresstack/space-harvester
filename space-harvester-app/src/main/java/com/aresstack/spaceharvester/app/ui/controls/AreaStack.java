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
 * teilen sie sich zunächst je zur Hälfte, drei zu Dritteln. Zwischen zwei Bereichen liegt ein Trenner wie überall
 * (Linie mit gelbem Griff): An ihm zieht man die Höhen der beiden Nachbarn; die Anteile bleiben, auch wenn ein
 * Bereich zwischendurch zu war. Bereiche, die gerade nicht gezeigt werden, bleiben unsichtbar in der Fläche.
 */
public final class AreaStack extends JPanel {

    /** Abstand zwischen zwei Bereichen, so dick wie ein Trenner. */
    public static final int GAP = ComicDivider.THICKNESS;
    /** Die Linie endet wie bei einem Trenner kurz vor den Rändern. */
    private static final int LINE_INSET = 8;
    private static final int LINE_WIDTH = 2;
    /** Länge des gelben Griffs höchstens, wie bei {@link ComicDivider}. */
    private static final int GRIP_LENGTH = 36;
    private static final int GRIP_WIDTH = 5;
    /** So niedrig lässt sich ein Bereich am Trenner höchstens ziehen. */
    public static final int SMALLEST_AREA = 80;
    /** Der Anteil eines Bereichs, solange niemand gezogen hat: alle gleich. */
    private static final double EVEN = 1.0;

    /** Je Bereich sein Anteil an der Höhe, im Verhältnis zu den anderen gezeigten. */
    private final java.util.Map<Component, Double> shares = new java.util.WeakHashMap<>();
    private final List<Runnable> shareListeners = new ArrayList<>();

    private final List<Component> shown = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    /** Die zuletzt gestellten Rechtecke, um nur echte Änderungen zu melden. */
    private List<java.awt.Rectangle> placed = List.of();

    public AreaStack() {
        super(null);
        setLayout(new Shares());
        setBackground(Comic.SURFACE);
        Pull pull = new Pull();
        addMouseListener(pull);
        addMouseMotionListener(pull);
    }

    /** Der Anteil eines Bereichs an der Höhe im Verhältnis zu den anderen (alle gleich: 1). */
    public double share(Component area) {
        return shares.getOrDefault(area, EVEN);
    }

    /** Setzt den Anteil eines Bereichs, etwa einen gemerkten. */
    public void share(Component area, double share) {
        if (share > 0) {
            shares.put(area, share);
            revalidate();
            repaint();
        }
    }

    /** Meldet, wenn jemand am Trenner die Anteile geändert hat. */
    public void onShares(Runnable listener) {
        shareListeners.add(listener);
    }

    /** Welcher Trenner an Höhe {@code y} liegt: die Nummer des Bereichs darunter, sonst -1. */
    private int dividerAt(int y) {
        for (int index = 1; index < shown.size(); index++) {
            int top = shown.get(index).getY();
            if (y >= top - GAP && y < top) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Zieht den Trenner über Bereich {@code below} um {@code moved} Pixel nach unten (negativ: nach oben): Der obere
     * Nachbar wächst, der untere schrumpft, keiner unter {@link #SMALLEST_AREA}. Die übrigen behalten ihre Höhe.
     */
    public void pull(int below, int moved) {
        if (below < 1 || below >= shown.size()) {
            return;
        }
        Component upper = shown.get(below - 1);
        Component lower = shown.get(below);
        int floor = Math.min(SMALLEST_AREA, Math.min(upper.getHeight(), lower.getHeight()));
        int step = Math.max(floor - upper.getHeight(), Math.min(lower.getHeight() - floor, moved));
        if (step == 0) {
            return;
        }
        // Die Anteile aller gezeigten Bereiche als ihre jetzigen Höhen, im Mittel 1: So behalten die übrigen ihre
        // Höhe, und ein später geöffneter Bereich bekommt mit Anteil 1 eine mittlere.
        double mean = 0;
        for (Component area : shown) {
            mean += area.getHeight();
        }
        mean = Math.max(1, mean / shown.size());
        for (Component area : shown) {
            int height = area.getHeight() + (area == upper ? step : area == lower ? -step : 0);
            shares.put(area, height / mean);
        }
        revalidate();
        doLayout();
        repaint();
        for (Runnable listener : List.copyOf(shareListeners)) {
            listener.run();
        }
    }

    /** Ziehen an einem Trenner zwischen zwei Bereichen. */
    private final class Pull extends java.awt.event.MouseAdapter {
        private int divider = -1;
        private int lastY;

        @Override
        public void mouseMoved(java.awt.event.MouseEvent event) {
            Cursors.set(AreaStack.this, java.awt.Cursor.getPredefinedCursor(dividerAt(event.getY()) > 0
                    ? java.awt.Cursor.N_RESIZE_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
        }

        @Override
        public void mousePressed(java.awt.event.MouseEvent event) {
            divider = javax.swing.SwingUtilities.isLeftMouseButton(event) ? dividerAt(event.getY()) : -1;
            lastY = event.getY();
        }

        @Override
        public void mouseDragged(java.awt.event.MouseEvent event) {
            if (divider > 0) {
                int before = shown.get(divider).getY();
                pull(divider, event.getY() - lastY);
                // Nur was der Trenner wirklich gewandert ist, zählt: An der Grenze läuft die Maus ihm nicht davon.
                lastY += shown.get(divider).getY() - before;
            }
        }

        @Override
        public void mouseReleased(java.awt.event.MouseEvent event) {
            divider = -1;
        }
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
                // Der gelbe Griff wie an jedem Trenner.
                int grip = Math.min(GRIP_LENGTH, getWidth() / 3);
                int middle = shown.get(index).getY() - GAP / 2;
                g.setColor(Comic.YELLOW);
                g.fillRoundRect((getWidth() - grip) / 2, middle - GRIP_WIDTH / 2 - 1, grip, GRIP_WIDTH, GRIP_WIDTH,
                        GRIP_WIDTH);
                g.setColor(Comic.INK);
                g.drawRoundRect((getWidth() - grip) / 2, middle - GRIP_WIDTH / 2 - 1, grip, GRIP_WIDTH, GRIP_WIDTH,
                        GRIP_WIDTH);
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
            double total = 0;
            for (Component area : shown) {
                total += share(area);
            }
            int left = room;
            for (int index = 0; index < count; index++) {
                // Der letzte Bereich bekommt den Rest der Teilung, damit unten nichts übersteht oder fehlt.
                int height = index == count - 1 ? left : (int) Math.round(room * share(shown.get(index)) / total);
                height = Math.max(0, Math.min(left, height));
                shown.get(index).setBounds(insets.left, y, width, height);
                left -= height;
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

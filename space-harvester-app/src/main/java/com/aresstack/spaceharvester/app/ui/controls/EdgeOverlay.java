package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Eine Fläche mit zwei schwebenden Plätzen am linken und rechten Fensterrand (Angelo 27.11.2026): Dorthin wandert der
 * Umschalter einer zugeklappten Seitenleiste. Er dreht sich senkrecht und beginnt direkt unter den Kopfleisten der
 * Arbeitsfläche, an deren Unterkante, als klappe er nach unten auf; von dort öffnet er die Leiste wieder. Die Plätze
 * liegen über dem übrigen Inhalt im schmalen Rand und nehmen ihm keinen Raum: Die Kopfleisten bleiben so breit, wie
 * sie sind. Ein leerer Platz ist unsichtbar und fängt keine Maus.
 */
public final class EdgeOverlay extends JPanel {

    /**
     * Abstand eines Platzes zur Fensterkante: die Strichbreite des Fensterrahmens ({@code ComicBorder.windowBorder}).
     * Die Plätze liegen damit ohne Luft am Rahmen (Angelo 28.11.2026), nicht mehr hinter dessen Polster.
     */
    public static final int FRAME_LINE = 2;
    /** Abstand eines Platzes zur Unterkante der Kopfleisten darüber. */
    public static final int HEAD_GAP = 4;

    private static final String FLOATING = "floating";

    private final JPanel left = slot();
    private final JPanel right = slot();
    private JComponent bar;
    /** Je Platz die Mitte, um die er steht ({@code null}: am Fensterrand); ohne Eintrag steht er am Rand. */
    private final java.util.Map<JComponent, java.util.function.Supplier<Point>> anchors = new java.util.HashMap<>();
    /** Je Platz die Höhe in ihm, die auf seiner Mitte liegt; ohne Eintrag seine halbe Höhe. */
    private final java.util.Map<JComponent, java.util.function.IntSupplier> focuses = new java.util.HashMap<>();
    /** Je Platz die Strecke, über die er sich ganz erstreckt (etwa ein Trenner); {@code null}: nicht gesetzt. */
    private final java.util.Map<JComponent, java.util.function.Supplier<java.awt.Rectangle>> spans =
            new java.util.HashMap<>();
    /** Was gerade mit der Maus getragen wird ({@link #carry}), oder {@code null}. */
    private JComponent carried;

    public EdgeOverlay() {
        super(new BorderLayout() {
            @Override
            public void addLayoutComponent(Component component, Object constraints) {
                if (!FLOATING.equals(constraints)) {
                    super.addLayoutComponent(component, constraints);
                }
            }
        });
        add(left, FLOATING, 0);
        add(right, FLOATING, 0);
    }

    private static JPanel slot() {
        JPanel slot = new JPanel(new BorderLayout()) {
            @Override
            public boolean contains(int x, int y) {
                // Nur wo wirklich ein Teil steht: Ein Platz kann länger sein als das, was auf ihm steht.
                for (Component part : getComponents()) {
                    if (part.contains(x - part.getX(), y - part.getY())) {
                        return true;
                    }
                }
                return false;
            }
        };
        slot.setOpaque(false);
        return slot;
    }

    /** Die Titelleiste; unter ihr stehen die Kopfleisten ({@link HeadBar}), unter denen die Plätze beginnen. */
    public void bar(JComponent bar) {
        this.bar = bar;
    }

    /** Der Platz am linken Rand. */
    public JComponent left() {
        return left;
    }

    /** Der Platz am rechten Rand. */
    public JComponent right() {
        return right;
    }

    /**
     * Hängt einen Platz an eine Mitte, etwa die eines Trenners ({@link DividerRail}): Solange {@code center} einen
     * Punkt liefert, steht der Platz waagerecht und senkrecht mittig darum statt oben am Fensterrand.
     */
    public void anchor(JComponent slot, java.util.function.Supplier<Point> center) {
        anchors.put(slot, center);
    }

    /**
     * Wie {@link #anchor(JComponent, java.util.function.Supplier)}, aber auf der Mitte liegt nicht die halbe Höhe des
     * Platzes, sondern {@code focus} Pixel unter seiner Oberkante: die Mitte der Knopfleiste, unter der noch einzelne
     * Kugeln stehen ({@link PearlString}).
     */
    public void anchor(JComponent slot, java.util.function.Supplier<Point> center,
            java.util.function.IntSupplier focus) {
        anchors.put(slot, center);
        focuses.put(slot, focus);
    }

    /**
     * Streckt einen Platz über eine ganze Strecke, etwa die Linie eines Trenners: Solange {@code line} ein Rechteck
     * liefert, ist der Platz so hoch wie es und steht waagerecht mittig darauf. Was auf ihm steht, verteilt sich
     * selbst über die Höhe ({@link PearlString}: jede Kugel auf Höhe ihres Bereichs).
     */
    public void span(JComponent slot, java.util.function.Supplier<java.awt.Rectangle> line) {
        spans.put(slot, line);
    }

    /**
     * Trägt {@code part} mit der Maus über allem anderen: seine Mitte steht auf {@code center}. Ein weiterer Aufruf
     * bewegt es; {@link #drop} nimmt es wieder weg.
     */
    public void carry(JComponent part, Point center) {
        if (carried != part) {
            drop();
            carried = part;
            add(part, FLOATING, 0);
        }
        Dimension size = part.getPreferredSize();
        part.setBounds(center.x - size.width / 2, center.y - size.height / 2, size.width, size.height);
        // Das Getragene ist neu und hat noch kein Layout: Ohne es blieben seine Teile leer und es unsichtbar.
        part.doLayout();
        repaint();
    }

    /** Nimmt das Getragene weg. */
    public void drop() {
        if (carried != null) {
            remove(carried);
            carried = null;
            repaint();
        }
    }

    /** Was gerade getragen wird (für Tests), oder {@code null}. */
    public JComponent carried() {
        return carried;
    }

    /** Wo ein Platz der Größe {@code size} steht: um seine Mitte, sonst an {@code edge} unter den Kopfleisten. */
    private Point place(JComponent slot, Dimension size, int edge, int top) {
        java.util.function.Supplier<Point> anchor = anchors.get(slot);
        Point center = anchor == null ? null : anchor.get();
        if (center == null) {
            return new Point(edge, top);
        }
        int x = Math.max(FRAME_LINE, Math.min(getWidth() - FRAME_LINE - size.width, center.x - size.width / 2));
        java.util.function.IntSupplier focus = focuses.get(slot);
        int above = focus == null ? size.height / 2 : focus.getAsInt();
        int y = Math.max(top, Math.min(getHeight() - size.height, center.y - above));
        return new Point(x, y);
    }

    /** Stellt {@code part} auf einen Platz ({@code null} leert ihn) und zeichnet neu. */
    public void place(JComponent slot, JComponent part) {
        if (part == null ? slot.getComponentCount() == 0 : part.getParent() == slot) {
            return;
        }
        slot.removeAll();
        if (part != null) {
            slot.add(part, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Die Plätze überlappen den Inhalt: Swing muss die Geschwister in ihrer Reihenfolge zeichnen. */
    @Override
    public boolean isOptimizedDrawingEnabled() {
        return left.getComponentCount() == 0 && right.getComponentCount() == 0 && carried == null;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        if (bar == null || !SwingUtilities.isDescendingFrom(bar, this)) {
            return;
        }
        Point origin = SwingUtilities.convertPoint(bar.getParent(), bar.getLocation(), this);
        int top = origin.y + bar.getHeight() + HeadBar.HEIGHT + HEAD_GAP;
        Dimension leftSize = left.getComponentCount() == 0 ? new Dimension() : left.getPreferredSize();
        Point leftAt = place(left, leftSize, FRAME_LINE, top);
        left.setBounds(leftAt.x, leftAt.y, leftSize.width, leftSize.height);
        Dimension rightSize = right.getComponentCount() == 0 ? new Dimension() : right.getPreferredSize();
        Point rightAt = place(right, rightSize, getWidth() - FRAME_LINE - rightSize.width, top);
        right.setBounds(rightAt.x, rightAt.y, rightSize.width, rightSize.height);
        for (JComponent slot : new JComponent[] {left, right}) {
            java.util.function.Supplier<java.awt.Rectangle> span = spans.get(slot);
            java.awt.Rectangle line = span == null || slot.getComponentCount() == 0 ? null : span.get();
            if (line != null) {
                int width = slot.getPreferredSize().width;
                slot.setBounds(Math.max(FRAME_LINE, Math.min(getWidth() - FRAME_LINE - width,
                        line.x + line.width / 2 - width / 2)), line.y, width, line.height);
            }
        }
    }
}

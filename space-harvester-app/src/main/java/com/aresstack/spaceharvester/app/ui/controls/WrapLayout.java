package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * Ein {@link FlowLayout}, dessen Wunschhöhe die umbrochenen Zeilen mitzählt: Ein normales FlowLayout bricht bei zu
 * wenig Breite zwar um, meldet aber die Höhe einer Zeile, sodass die weiteren Zeilen abgeschnitten werden (Reiter in
 * einer schmalen Reiterzeile). Hier wächst der Behälter mit; die Breite kommt vom Behälter selbst oder, solange der
 * noch keine hat, von seinem nächsten Vorfahren mit Breite.
 */
public final class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    /**
     * Nach dem Auslegen ist die eigene Breite bekannt; reicht die zugeteilte Höhe nicht für die umbrochenen Zeilen,
     * verlangt der Behälter ein neues Layout. Nur nach oben, damit das nicht endlos läuft.
     */
    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        if (target.getWidth() > 0 && preferredLayoutSize(target).height > target.getHeight()) {
            target.revalidate();
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = availableWidth(target);
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + getHgap() * 2;
            int maxWidth = targetWidth - horizontalInsetsAndGap;
            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component component = target.getComponent(i);
                if (!component.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += getHgap();
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);
            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + getVgap() * 2;
            // In einem Scrollbereich die Breite des Sichtfensters nicht aufdrücken.
            Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width -= getHgap() + 1;
            }
            return dim;
        }
    }

    /** Die Breite, auf die umbrochen wird: die eigene, sonst die des ersten Vorfahren mit Breite. */
    private static int availableWidth(Container target) {
        Container current = target;
        while (current != null && current.getWidth() <= 0) {
            current = current.getParent();
        }
        return current == null ? Integer.MAX_VALUE : current.getWidth();
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) {
            dim.height += getVgap();
        }
        dim.height += rowHeight;
    }
}

package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Der Tooltip für eine abgeschnittene Baumzeile (Angelo, 27.11.2026): Endet der Text einer Zeile rechts mit „…“ oder
 * ragt er über den sichtbaren Bereich hinaus, zeigt der Tooltip den vollständigen Eintrag. Gilt für jeden Baum, dessen
 * Zeilen ein {@link JLabel} zeichnet, und für jeden Knoten gleich: gemessen wird nur, was der Renderer der Zeile
 * tatsächlich darstellen soll, gegen den Platz, den die Zeile im sichtbaren Bereich hat.
 */
public final class TreeTextTip {

    private TreeTextTip() {
    }

    /**
     * Der vollständige Text der Zeile {@code path}, wenn er dort nicht ganz zu sehen ist, sonst {@code null}.
     * Ränder des Labels (etwa Spalten rechts) und das Symbol vor dem Text verkleinern den Platz.
     */
    public static String fullText(JTree tree, TreePath path) {
        if (tree == null || path == null) {
            return null;
        }
        int row = tree.getRowForPath(path);
        Rectangle bounds = tree.getPathBounds(path);
        if (row < 0 || bounds == null || tree.getCellRenderer() == null) {
            return null;
        }
        Object value = path.getLastPathComponent();
        Component cell = tree.getCellRenderer().getTreeCellRendererComponent(tree, value, tree.isRowSelected(row),
                tree.isExpanded(row), tree.getModel().isLeaf(value), row, false);
        if (!(cell instanceof JLabel label)) {
            return null;
        }
        String text = label.getText();
        if (text == null || text.isBlank() || text.regionMatches(true, 0, "<html", 0, 5)) {
            return null;
        }
        Insets insets = label.getInsets();
        Icon icon = label.getIcon();
        int iconWidth = icon == null ? 0 : icon.getIconWidth() + label.getIconTextGap();
        Rectangle visible = tree.getVisibleRect();
        int right = Math.min(bounds.x + bounds.width - insets.right, visible.x + visible.width);
        int room = right - bounds.x - insets.left - iconWidth;
        int needed = label.getFontMetrics(label.getFont()).stringWidth(text);
        return needed > room ? text : null;
    }
}

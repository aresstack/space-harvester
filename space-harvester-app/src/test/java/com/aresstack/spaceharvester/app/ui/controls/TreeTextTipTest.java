package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Angelo 27.11.2026: Ist ein Eintrag im Baum rechts abgeschnitten, zeigt der Tooltip den vollständigen Text. */
class TreeTextTipTest {

    private static final String LONG = "Eine sehr lange Seitenüberschrift, die in keinen schmalen Baum passt";

    private static JTree tree(int width) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Wurzel");
        root.add(new DefaultMutableTreeNode("Kurz"));
        root.add(new DefaultMutableTreeNode(LONG));
        JTree tree = new JTree(root);
        tree.setSize(width, 300);
        return tree;
    }

    private static String tip(JTree tree, int row) throws Exception {
        AtomicReference<String> tip = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> tip.set(TreeTextTip.fullText(tree, tree.getPathForRow(row))));
        return tip.get();
    }

    @Test
    void aCutEntryShowsItsFullText() throws Exception {
        JTree tree = tree(120);
        assertThat(tip(tree, 2)).isEqualTo(LONG);
    }

    @Test
    void anEntryThatFitsHasNoTip() throws Exception {
        JTree tree = tree(1200);
        assertThat(tip(tree, 1)).isNull();
        assertThat(tip(tree, 2)).isNull();
    }

    @Test
    void columnsInTheRightMarginCountAsTaken() throws Exception {
        // Zeilen genau so breit wie der Baum, rechts ein Rand für Symbole (wie der Wissensquellen-Baum).
        JTree tree = tree(1200);
        int fullWidth = tree.getPathBounds(new TreePath(new Object[] {tree.getModel().getRoot(),
                tree.getModel().getChild(tree.getModel().getRoot(), 1)})).width;
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                                                          boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 60));
                return this;
            }

            @Override
            public java.awt.Dimension getPreferredSize() {
                return new java.awt.Dimension(fullWidth, super.getPreferredSize().height);
            }
        });
        assertThat(tip(tree, 2)).isEqualTo(LONG);
    }

    @Test
    void noPathNoTip() throws Exception {
        assertThat(tip(tree(120), 9)).isNull();
    }
}

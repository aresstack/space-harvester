package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * Eine Reihe Symbolknöpfe, mittig, die nie umbricht: Reicht die Breite nicht, verschwinden die hinteren Knöpfe in das
 * Menü „Weitere Werkzeuge“ (Menüsymbol) am Ende, mit Symbol und Tooltip als Text. So bleibt eine Fußleiste einzeilig, auch bei
 * 960 px Fensterbreite.
 */
public final class OverflowRow extends JPanel {

    private static final int GAP = 4;

    private final List<JComponent> parts;
    private final com.aresstack.spaceharvester.app.ui.IconButton more;
    private final List<JComponent> hidden = new ArrayList<>();

    public OverflowRow(JComponent... parts) {
        super(null);
        setOpaque(false);
        this.parts = List.of(parts);
        for (JComponent part : parts) {
            add(part);
        }
        more = new com.aresstack.spaceharvester.app.ui.IconButton(
                com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.menu(), "Weitere Werkzeuge");
        more.addActionListener(e -> showMore());
        add(more);
    }

    /** Die Knöpfe, die gerade im Menü stehen (für Tests). */
    public List<JComponent> hiddenParts() {
        doLayout();
        return List.copyOf(hidden);
    }

    @Override
    public Dimension getPreferredSize() {
        int width = 0;
        int height = 0;
        for (JComponent part : parts) {
            if (part.isVisible()) {
                Dimension size = part.getPreferredSize();
                width += size.width + GAP;
                height = Math.max(height, size.height);
            }
        }
        Insets insets = getInsets();
        return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension size = more.getPreferredSize();
        return new Dimension(size.width, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        hidden.clear();
        Insets insets = getInsets();
        int available = getWidth() - insets.left - insets.right;
        int moreWidth = more.getPreferredSize().width + GAP;
        int total = 0;
        for (JComponent part : parts) {
            if (part.isVisible()) {
                total += part.getPreferredSize().width + GAP;
            }
        }
        boolean overflow = total > available;
        int budget = overflow ? available - moreWidth : available;
        int used = 0;
        List<JComponent> shown = new ArrayList<>();
        for (JComponent part : parts) {
            if (!part.isVisible()) {
                continue;
            }
            int width = part.getPreferredSize().width + GAP;
            if (hidden.isEmpty() && used + width <= budget) {
                shown.add(part);
                used += width;
            } else {
                hidden.add(part);
            }
        }
        if (overflow) {
            used += moreWidth;
        }
        int x = insets.left + Math.max(0, (available - used) / 2);
        int height = getHeight() - insets.top - insets.bottom;
        for (JComponent part : shown) {
            x = place(part, x, insets.top, height);
        }
        for (JComponent part : hidden) {
            part.setBounds(0, 0, 0, 0);
        }
        more.setVisible(overflow);
        if (overflow) {
            place(more, x, insets.top, height);
        }
    }

    private static int place(Component part, int x, int top, int height) {
        Dimension size = part.getPreferredSize();
        part.setBounds(x, top + Math.max(0, (height - size.height) / 2), size.width, size.height);
        return x + size.width + GAP;
    }

    private void showMore() {
        JPopupMenu menu = new JPopupMenu();
        for (JComponent part : hidden) {
            if (part instanceof AbstractButton button) {
                JMenuItem item = new JMenuItem(button.getToolTipText(), button.getIcon());
                item.setEnabled(button.isEnabled());
                item.addActionListener(e -> button.doClick());
                menu.add(item);
            }
        }
        if (menu.getComponentCount() > 0) {
            menu.show(more, 0, -menu.getPreferredSize().height);
        }
    }
}

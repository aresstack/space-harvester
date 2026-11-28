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

    /** Was in der Reihe steht, in ihrer Reihenfolge; bei {@link #around} die Kinder des fremden Behälters. */
    private final java.util.function.Supplier<List<JComponent>> parts;
    /** Der Behälter, in dem die Teile liegen: diese Reihe selbst oder der fremde Behälter ({@link #around}). */
    private final JComponent host;
    private final com.aresstack.spaceharvester.app.ui.IconButton more;
    private final List<JComponent> hidden = new ArrayList<>();

    public OverflowRow(JComponent... parts) {
        super(null);
        setOpaque(false);
        List<JComponent> fixed = List.of(parts);
        this.parts = () -> fixed;
        this.host = this;
        for (JComponent part : fixed) {
            add(part);
        }
        more = more();
    }

    private OverflowRow(JComponent bar) {
        super(null);
        setOpaque(false);
        this.host = bar;
        this.parts = () -> {
            List<JComponent> children = new ArrayList<>();
            for (Component child : bar.getComponents()) {
                if (child instanceof JComponent part) {
                    children.add(part);
                }
            }
            return children;
        };
        // Die Reihe stellt die Kinder selbst; ein eigenes Layout des Behälters stellte sie sonst wieder um.
        bar.setLayout(null);
        add(bar);
        more = more();
        // Das Menü liegt über dem Behälter, der die ganze Reihe füllt.
        setComponentZOrder(more, 0);
    }

    /**
     * Dieselbe Reihe um einen Behälter, dessen Knöpfe einem anderen gehören und sich ändern (etwa die Reiter von
     * {@code PillTabs} in einer Fußleiste, Angelo 28.11.2026): Die Knöpfe bleiben Kinder von {@code bar}, die Reihe
     * stellt sie nur in eine Zeile und nimmt die hinteren hinter das Menü, wenn die Breite nicht reicht.
     */
    public static OverflowRow around(JComponent bar) {
        return new OverflowRow(bar);
    }

    private com.aresstack.spaceharvester.app.ui.IconButton more() {
        com.aresstack.spaceharvester.app.ui.IconButton button = new com.aresstack.spaceharvester.app.ui.IconButton(
                com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.menu(), "Weitere Werkzeuge");
        button.addActionListener(e -> showMore());
        add(button);
        return button;
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
        for (JComponent part : parts.get()) {
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
        for (JComponent part : parts.get()) {
            if (part.isVisible()) {
                total += part.getPreferredSize().width + GAP;
            }
        }
        boolean overflow = total > available;
        int budget = overflow ? available - moreWidth : available;
        int used = 0;
        List<JComponent> shown = new ArrayList<>();
        for (JComponent part : parts.get()) {
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
        // Ein gewählter Umschalter bleibt in der Reihe: Er tauscht mit dem letzten, der noch hineinpasste.
        for (JComponent part : List.copyOf(hidden)) {
            if (part instanceof AbstractButton button && button.isSelected() && !shown.isEmpty()) {
                JComponent last = shown.remove(shown.size() - 1);
                used += part.getPreferredSize().width - last.getPreferredSize().width;
                shown.add(part);
                hidden.set(hidden.indexOf(part), last);
                // Ist der gewählte breiter als der getauschte (Reiter mit Text), weichen weitere, bis er und das
                // Menü in die Reihe passen; das Menü darf nie hinausgeschoben werden.
                while (used > budget && shown.size() > 1) {
                    JComponent gone = shown.remove(shown.size() - 2);
                    used -= gone.getPreferredSize().width + GAP;
                    hidden.add(hidden.indexOf(last), gone);
                    last = gone;
                }
            }
        }
        if (overflow) {
            used += moreWidth;
        }
        int x = insets.left + Math.max(0, (available - used) / 2);
        int height = getHeight() - insets.top - insets.bottom;
        if (host != this) {
            // Der fremde Behälter füllt die Reihe; seine Kinder stehen darin an denselben Stellen.
            host.setBounds(0, 0, getWidth(), getHeight());
        }
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

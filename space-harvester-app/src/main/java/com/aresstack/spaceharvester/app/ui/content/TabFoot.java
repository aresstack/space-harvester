package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Die Unterreiter einer Ansicht in der unteren Leiste mit der Lupe (Angelo 23.11.2026): die Pillen mittig unter der
 * ganzen Leiste, ein zweiter Satz Pillen (etwa die Unterreiter von „Begriffe“) hinter einem dünnen Strich daneben, ein
 * Element rechts in der Ecke (etwa „mit Unterseiten“). Reicht die Breite nicht für alles in einer Zeile, rückt der
 * zweite Satz in eine zweite Zeile. Füllt die Zeile rechts der Lupe ({@code View.TOOLS_FILL}). Nur auf dem EDT.
 */
final class TabFoot extends JPanel {

    private static final int GAP = 12;

    private final JComponent tabs;
    private final JComponent trailing;
    private final JComponent divider = new Divider();
    private JComponent secondary;

    /**
     * @param tabs     die Pillen, mittig
     * @param trailing rechts in der Ecke oder {@code null}
     */
    TabFoot(JComponent tabs, JComponent trailing) {
        super(null);
        this.tabs = tabs;
        this.trailing = trailing;
        setOpaque(false);
        setLayout(new Layout());
        add(tabs);
        add(divider);
        divider.setVisible(false);
        if (trailing != null) {
            add(trailing);
        }
        putClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL, Boolean.TRUE);
    }

    /** Der zweite Satz Pillen hinter dem Strich; {@code null} nimmt ihn weg. */
    void secondary(JComponent next) {
        if (next == secondary) {
            return;
        }
        if (secondary != null) {
            remove(secondary);
        }
        secondary = next;
        if (next != null) {
            add(next);
        }
        divider.setVisible(next != null);
        revalidate();
        repaint();
    }

    JComponent secondary() {
        return secondary;
    }

    JComponent tabs() {
        return tabs;
    }

    JComponent trailing() {
        return trailing;
    }

    /** Ein dünner senkrechter Strich zwischen den beiden Sätzen. */
    private static final class Divider extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 18);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Comic.LINE);
            g.fillRect(0, 0, 1, getHeight());
        }
    }

    private final class Layout implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        private int row(boolean withSecondary) {
            int width = tabs.getPreferredSize().width;
            if (withSecondary && secondary != null) {
                width += GAP + 1 + GAP + secondary.getPreferredSize().width;
            }
            return width;
        }

        private int trailingWidth() {
            return trailing == null || !trailing.isVisible() ? 0 : trailing.getPreferredSize().width + GAP;
        }

        /** Ob alles in eine Zeile passt; ohne bekannte Breite ja. */
        private boolean oneLine(Container parent) {
            int width = parent.getWidth();
            return secondary == null || width <= 0 || row(true) + 2 * trailingWidth() <= width;
        }

        private int lineHeight() {
            int height = tabs.getPreferredSize().height;
            if (trailing != null) {
                height = Math.max(height, trailing.getPreferredSize().height);
            }
            if (secondary != null) {
                height = Math.max(height, secondary.getPreferredSize().height);
            }
            return height;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int lines = oneLine(parent) ? 1 : 2;
            int height = lines * lineHeight() + (lines - 1) * 4;
            return new Dimension(row(true) + trailingWidth() + in.left + in.right, height + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int left = in.left;
            int right = parent.getWidth() - in.right;
            int top = in.top;
            int height = Math.max(0, parent.getHeight() - in.top - in.bottom);
            boolean one = oneLine(parent);
            int line = one ? height : (height - 4) / 2;
            int end = right;
            if (trailing != null && trailing.isVisible()) {
                Dimension size = trailing.getPreferredSize();
                end = right - size.width;
                trailing.setBounds(end, top + (line - size.height) / 2, size.width, size.height);
                end -= GAP;
            }
            // Mittig unter der ganzen Leiste, nicht nur rechts der Lupe; nie unter das Element rechts.
            int center = (left + right) / 2;
            Container bar = parent.getParent() == null ? null : parent.getParent().getParent();
            if (bar != null && bar.getWidth() > 0) {
                int offset = SwingUtilities.convertPoint(parent, 0, 0, bar).x;
                center = bar.getWidth() / 2 - offset;
            }
            int width = Math.min(row(one), Math.max(0, end - left));
            int x = Math.max(left, Math.min(center - width / 2, end - width));
            Dimension own = tabs.getPreferredSize();
            int tabsWidth = Math.min(own.width, width);
            tabs.setBounds(x, top + (line - own.height) / 2, tabsWidth, own.height);
            if (secondary == null) {
                return;
            }
            Dimension more = secondary.getPreferredSize();
            if (one) {
                int dx = x + tabsWidth + GAP;
                divider.setBounds(dx, top + (line - 18) / 2, 1, 18);
                int sx = dx + 1 + GAP;
                secondary.setBounds(sx, top + (line - more.height) / 2, Math.max(0, Math.min(more.width, end - sx)),
                        more.height);
            } else {
                divider.setBounds(0, 0, 0, 0);
                int secondTop = top + line + 4;
                int sw = Math.min(more.width, Math.max(0, end - left));
                int sx = Math.max(left, Math.min(center - sw / 2, end - sw));
                secondary.setBounds(sx, secondTop + (line - more.height) / 2, sw, more.height);
            }
        }
    }
}

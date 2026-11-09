package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Setzt die Suchleiste mittig in den freien Raum der Kopfzeile (#515): links und rechts gleich viel Abstand, das Feld
 * wächst mit dem Fenster bis {@link #MAX_WIDTH} und schrumpft bis {@link #MIN_WIDTH}; nie über die Icon-Gruppen, denn
 * es bekommt nur den Raum zwischen ihnen.
 */
final class SearchBarLayout implements LayoutManager {

    static final int MAX_WIDTH = 560;
    static final int MIN_WIDTH = 140;
    /** Mindestabstand zu den Icon-Gruppen links und rechts. */
    static final int GAP = 16;

    private SearchBarLayout() {
    }

    static JPanel wrap(JComponent bar) {
        JPanel panel = new JPanel(new SearchBarLayout());
        panel.setOpaque(false);
        panel.add(bar);
        return panel;
    }

    @Override
    public void layoutContainer(Container parent) {
        if (parent.getComponentCount() == 0) {
            return;
        }
        Component bar = parent.getComponent(0);
        Insets in = parent.getInsets();
        int free = parent.getWidth() - in.left - in.right;
        int width = Math.max(Math.min(MAX_WIDTH, free - 2 * GAP), Math.min(MIN_WIDTH, Math.max(0, free)));
        width = Math.min(width, Math.max(0, free));
        int height = Math.min(bar.getPreferredSize().height, parent.getHeight() - in.top - in.bottom);
        int x = in.left + (free - width) / 2;
        int y = in.top + (parent.getHeight() - in.top - in.bottom - height) / 2;
        bar.setBounds(x, y, width, height);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        int height = parent.getComponentCount() == 0 ? 0 : parent.getComponent(0).getPreferredSize().height;
        return new Dimension(MIN_WIDTH + 2 * GAP, height);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return preferredLayoutSize(parent);
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }
}

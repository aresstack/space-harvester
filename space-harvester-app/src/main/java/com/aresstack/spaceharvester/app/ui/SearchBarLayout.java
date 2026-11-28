package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Setzt die Suchleiste rechtsbündig in den freien Raum der Kopfzeile (#515; Angelo 27.11.2026: ganz rechts, direkt vor
 * der Gruppe rechts, und auch im großen Fenster nicht breit, denn es kommt wenig Text hinein). Das Feld wächst mit
 * dem Fenster bis {@link #MAX_WIDTH} und schrumpft bis {@link #MIN_WIDTH}; nie über die Icon-Gruppen, denn es bekommt
 * nur den Raum zwischen ihnen. Ein zweites Kind (Zurück/Vor, Angelo 24.11.2026) klebt direkt links am Feld; der freie
 * Platz liegt links davor.
 */
final class SearchBarLayout implements LayoutManager {

    static final int MAX_WIDTH = 360;
    static final int MIN_WIDTH = 140;
    /** Mindestabstand zur Icon-Gruppe links. */
    static final int GAP = 16;
    /** Abstand zwischen Zurück/Vor und dem Suchfeld. */
    static final int LEADING_GAP = 4;

    private SearchBarLayout() {
    }

    static JPanel wrap(JComponent bar) {
        return wrap(bar, null);
    }

    /** Wie {@link #wrap(JComponent)}, mit {@code leading} direkt links am Feld. */
    static JPanel wrap(JComponent bar, JComponent leading) {
        JPanel panel = new JPanel(new SearchBarLayout());
        panel.setOpaque(false);
        panel.add(bar);
        if (leading != null) {
            panel.add(leading);
        }
        return panel;
    }

    /** Breite der Knöpfe links am Feld samt Abstand; 0, wenn keine da oder unsichtbar sind. */
    private static int leadingWidth(Container parent) {
        if (parent.getComponentCount() < 2 || !parent.getComponent(1).isVisible()) {
            return 0;
        }
        return parent.getComponent(1).getPreferredSize().width + LEADING_GAP;
    }

    @Override
    public void layoutContainer(Container parent) {
        if (parent.getComponentCount() == 0) {
            return;
        }
        Component bar = parent.getComponent(0);
        Insets in = parent.getInsets();
        int lead = leadingWidth(parent);
        int free = parent.getWidth() - in.left - in.right - lead;
        int width = Math.max(Math.min(MAX_WIDTH, free - GAP), Math.min(MIN_WIDTH, Math.max(0, free)));
        width = Math.min(width, Math.max(0, free));
        int inner = parent.getHeight() - in.top - in.bottom;
        int height = Math.min(bar.getPreferredSize().height, inner);
        int x = in.left + lead + free - width;
        bar.setBounds(x, in.top + (inner - height) / 2, width, height);
        if (lead > 0) {
            Component leading = parent.getComponent(1);
            Dimension size = leading.getPreferredSize();
            int h = Math.min(size.height, inner);
            leading.setBounds(x - lead, in.top + (inner - h) / 2, size.width, h);
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        int height = 0;
        for (Component child : parent.getComponents()) {
            if (child.isVisible()) {
                height = Math.max(height, child.getPreferredSize().height);
            }
        }
        return new Dimension(MIN_WIDTH + GAP + leadingWidth(parent), height);
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

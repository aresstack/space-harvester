package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * Der Inhalt eines Reiters in einem Scrollbereich: folgt der Breite des Sichtfensters, damit umbrechende Texte
 * (Belege, Hinweise) umbrechen statt den Inhalt zu verbreitern und einen waagerechten Balken zu erzeugen; in der Höhe
 * wächst er mit dem Inhalt und scrollt senkrecht.
 */
public final class ScrollColumn extends JPanel implements Scrollable {

    public ScrollColumn(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(16, visible.height - 32);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}

package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.LayoutManager;

/**
 * Die Kopfleiste über jedem Bereich des Fensters (Angelo 27.11.2026): linke Seitenleiste, Mitte und rechte
 * Seitenleiste haben dieselbe Höhe, und die Linie darunter läuft auf einer Höhe durch. Die Höhe steht nur hier; wer
 * eine Kopfleiste braucht, nimmt diese Klasse (oder erbt von ihr) und setzt keine eigene Höhe und keine eigene Linie.
 * Der Inhalt steht in dem Layout, das der Bereich mitgibt, und sitzt darin senkrecht mittig.
 */
public class HeadBar extends JPanel {

    /** Höhe der Kopfleiste samt Linie darunter. */
    public static final int HEIGHT = 35;

    /**
     * @param layout das Layout des Inhalts
     * @param left   Innenabstand links
     * @param right  Innenabstand rechts
     */
    public HeadBar(LayoutManager layout, int left, int right) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(0, left, 0, right)));
    }

    @Override
    public final Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    @Override
    public final Dimension getMinimumSize() {
        return new Dimension(super.getMinimumSize().width, HEIGHT);
    }

    @Override
    public final Dimension getMaximumSize() {
        return new Dimension(super.getMaximumSize().width, HEIGHT);
    }
}

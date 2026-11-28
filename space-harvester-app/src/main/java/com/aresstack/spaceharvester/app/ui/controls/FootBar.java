package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Dimension;
import java.awt.LayoutManager;
import javax.swing.JPanel;

/**
 * Die eine Basis jeder Fußleiste der App (Angelo 28.11.2026: alle Fußleisten von derselben Klasse, gleiche Höhe):
 * die Leiste mit der Lupe unter jeder Arbeitsfläche und in jeder Seitenleiste ebenso wie die Fußleiste der
 * Editor-Seitenleisten. Die Höhe ist immer {@link Comic#FOOT_HEIGHT}, gleich was auf der Leiste steht; so stehen alle
 * Fußleisten des Fensters auf einer Linie. Was nicht in die Breite passt, wächst nicht nach unten, sondern klappt weg
 * ({@link OverflowRow}).
 */
public abstract class FootBar extends JPanel {

    protected FootBar(LayoutManager layout) {
        super(layout);
    }

    @Override
    public final Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, Comic.FOOT_HEIGHT);
    }

    @Override
    public final Dimension getMinimumSize() {
        return new Dimension(super.getMinimumSize().width, Comic.FOOT_HEIGHT);
    }

    @Override
    public final Dimension getMaximumSize() {
        return new Dimension(super.getMaximumSize().width, Comic.FOOT_HEIGHT);
    }
}

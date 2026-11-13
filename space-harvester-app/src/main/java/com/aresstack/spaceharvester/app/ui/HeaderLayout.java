package com.aresstack.spaceharvester.app.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;

/**
 * Kopfzeile des Hauptfensters: links Titel und Icon-Gruppen, rechts Begriffe/Aufträge/Verwaltung, in der Mitte das
 * Suchfeld (#515). Reicht der Platz dem Suchfeld nicht für seine Mindestbreite ({@link SearchBarLayout#MIN_WIDTH}),
 * gibt die linke Gruppe Breite ab, nie das Suchfeld: der Titel wird dort als einziger Teil gekürzt
 * („Space Har…“), die Knöpfe bleiben ganz. So hängt die Kopfzeile nicht an den Schriftmaßen des Rechners.
 */
final class HeaderLayout extends BorderLayout {

    HeaderLayout(int hgap) {
        super(hgap, 0);
    }

    @Override
    public void layoutContainer(Container parent) {
        super.layoutContainer(parent);
        Component west = getLayoutComponent(WEST);
        Component center = getLayoutComponent(CENTER);
        if (west == null || center == null || !west.isVisible() || !center.isVisible()) {
            return;
        }
        int lack = center.getMinimumSize().width - center.getWidth();
        if (lack <= 0) {
            return;
        }
        int shrink = Math.min(lack, west.getWidth());
        west.setSize(west.getWidth() - shrink, west.getHeight());
        center.setBounds(center.getX() - shrink, center.getY(), center.getWidth() + shrink, center.getHeight());
    }
}

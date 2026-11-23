package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;

/**
 * Volle Breite, eigene Höhe, senkrecht mittig: Was eine Leiste so hält, steht auf derselben Höhe wie die Knöpfe der
 * Nachbarleisten, auch wenn die Leiste höher ist als ihr Inhalt (wie die Mitte der Fußleiste, #1138).
 */
public final class MiddleLayout extends BorderLayout {

    @Override
    public void layoutContainer(Container parent) {
        Insets in = parent.getInsets();
        int width = Math.max(0, parent.getWidth() - in.left - in.right);
        int height = Math.max(0, parent.getHeight() - in.top - in.bottom);
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            int own = Math.min(height, child.getPreferredSize().height);
            child.setBounds(in.left, in.top + (height - own) / 2, width, own);
        }
    }
}

package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Objects;

/**
 * Ein Symbol auf den Kopf gestellt: derselbe Pfeil zeigt nach unten statt nach oben, etwa am Knopf, der eine Fläche
 * auf- und wieder zuklappt (Angelo 28.11.2026: „der Knopf dreht sich einfach“).
 */
public final class Flipped implements Icon {

    private final Icon icon;

    public Flipped(Icon icon) {
        this.icon = Objects.requireNonNull(icon, "icon");
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        // An der waagerechten Mitte des Symbols spiegeln.
        g2.translate(0, 2 * y + getIconHeight());
        g2.scale(1, -1);
        icon.paintIcon(c, g2, x, y);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return icon.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return icon.getIconHeight();
    }
}

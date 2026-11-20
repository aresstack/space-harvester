package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;

/**
 * „Transkript erzeugen“ (Angelo, 20.11.2026): eine Sprechblase mit Textzeilen statt des Mikrofons, gesprochenes Wort
 * wird zu Text; in der Linienstärke und Größe der übrigen Symbole der Leiste mit der Lupe.
 */
final class TranscribeIcon extends StrokeIcon {

    @Override
    protected void paint(Graphics2D g2) {
        g2.draw(new RoundRectangle2D.Double(1.5, 2, 12, 8.5, 3, 3));
        // Der Zipfel der Sprechblase unten links.
        g2.drawLine(4, 10, 3, 13);
        g2.drawLine(3, 13, 7, 10);
        // Zwei Textzeilen.
        g2.drawLine(4, 5, 11, 5);
        g2.drawLine(4, 7, 9, 7);
    }
}

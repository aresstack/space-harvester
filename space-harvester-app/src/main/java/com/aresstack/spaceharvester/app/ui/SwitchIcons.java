package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * Symbole der Umschalter in den Seitenleisten (Angelo 27.11.2026: Umschalter einheitlich nur mit Symbolen, der Name
 * steht im Tooltip). Gezeichnet in der Schriftfarbe des Knopfs.
 */
final class SwitchIcons {

    private SwitchIcons() {
    }

    /** Artefakte: ein Würfel, das fertige Stück, das die Arbeit hervorgebracht hat. */
    static StrokeIcon artifacts() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D.Float cube = new Path2D.Float();
                cube.moveTo(8f, 1.5f);
                cube.lineTo(14f, 4.7f);
                cube.lineTo(14f, 11.3f);
                cube.lineTo(8f, 14.5f);
                cube.lineTo(2f, 11.3f);
                cube.lineTo(2f, 4.7f);
                cube.closePath();
                g2.draw(cube);
                g2.draw(new Line2D.Float(2f, 4.7f, 8f, 7.9f));
                g2.draw(new Line2D.Float(14f, 4.7f, 8f, 7.9f));
                g2.draw(new Line2D.Float(8f, 7.9f, 8f, 14.5f));
            }
        };
    }

    /** Aufträge, die man selbst gestartet hat: der Auftragszettel auf dem Klemmbrett. */
    static StrokeIcon jobs() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(3, 3, 10, 12, 2, 2);
                g2.drawRoundRect(6, 1, 4, 3, 2, 2);
                g2.draw(new Line2D.Float(5.5f, 8f, 10.5f, 8f));
                g2.draw(new Line2D.Float(5.5f, 11f, 9f, 11f));
            }
        };
    }

    /** Hintergrund: die Uhr, Arbeit, die von selbst nebenher läuft. */
    static StrokeIcon background() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Ellipse2D.Float(1.5f, 1.5f, 13f, 13f));
                Path2D.Float hands = new Path2D.Float();
                hands.moveTo(8f, 4.5f);
                hands.lineTo(8f, 8f);
                hands.lineTo(10.6f, 9.6f);
                g2.draw(hands);
            }
        };
    }
}

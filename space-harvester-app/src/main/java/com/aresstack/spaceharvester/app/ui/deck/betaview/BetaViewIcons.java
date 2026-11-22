package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;

/** Gezeichnete Symbole der Werkzeuge des BetaView-Bereichs (keine Schriftzeichen). */
final class BetaViewIcons {

    private BetaViewIcons() {
    }

    /** Zwei Kreispfeile: neu laden (wie überall in der App). */
    static StrokeIcon refresh() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 20, 140, Arc2D.OPEN));
                g2.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 200, 140, Arc2D.OPEN));
                g2.drawLine(3, 4, 3, 7);
                g2.drawLine(3, 7, 6, 7);
                g2.drawLine(13, 12, 13, 9);
                g2.drawLine(13, 9, 10, 9);
            }
        };
    }

    /** Senkrechter Strich mit Pfeil nach links: erste Seite. */
    static StrokeIcon first() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(4, 3, 4, 13);
                g2.drawLine(12, 3, 7, 8);
                g2.drawLine(7, 8, 12, 13);
            }
        };
    }

    /** Pfeil nach rechts mit senkrechtem Strich: letzte Seite. */
    static StrokeIcon last() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(12, 3, 12, 13);
                g2.drawLine(4, 3, 9, 8);
                g2.drawLine(9, 8, 4, 13);
            }
        };
    }

    /** Pfeil nach unten auf eine Schale mit drei Strichen daneben: herunterladen mit Optionen. */
    static StrokeIcon downloadOptions() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(6, 2, 6, 10);
                g2.drawLine(3, 7, 6, 10);
                g2.drawLine(9, 7, 6, 10);
                g2.drawLine(2, 13, 10, 13);
                g2.drawLine(12, 4, 15, 4);
                g2.drawLine(12, 8, 15, 8);
                g2.drawLine(12, 12, 15, 12);
            }
        };
    }

    /** Fünfzackiger Stern als Umriss: merken. */
    static StrokeIcon star() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                java.awt.geom.Path2D.Double star = new java.awt.geom.Path2D.Double();
                for (int i = 0; i < 10; i++) {
                    double radius = i % 2 == 0 ? 6.5 : 2.8;
                    double angle = Math.PI / 2 + i * Math.PI / 5;
                    double x = 8 + radius * Math.cos(angle);
                    double y = 8.5 - radius * Math.sin(angle);
                    if (i == 0) {
                        star.moveTo(x, y);
                    } else {
                        star.lineTo(x, y);
                    }
                }
                star.closePath();
                g2.draw(star);
            }
        };
    }

    /** Diskette: Suche speichern. */
    static StrokeIcon save() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(2, 2, 12, 12, 2, 2);
                g2.drawRect(5, 2, 6, 4);
                g2.drawRect(4, 9, 8, 5);
            }
        };
    }
}

package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import javax.swing.Icon;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;

/** Symbole des Chats, die der Comic-Baukasten nicht mitbringt, im selben Strich wie {@link PageTabIcons}. */
final class ChatIcons {

    private ChatIcons() {
    }

    /** Chat: eine Sprechblase mit Schwanz unten links. */
    static Icon chat() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {13, 13, 6, 3, 4, 2, 2, 13}, new int[] {3, 10, 10, 13, 10, 10, 3, 3}, 8);
            }
        };
    }

    /** Websuche: die Weltkugel mit Längen- und Breitenlinie. */
    static Icon globe() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Ellipse2D.Float(2, 2, 11, 11));
                g2.draw(new Ellipse2D.Float(5.5f, 2, 4, 11));
                g2.drawLine(2, 7, 13, 7);
            }
        };
    }

    /** Denkaufwand: eine Gedankenblase mit zwei kleinen Kreisen. */
    static Icon thought() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.draw(new Ellipse2D.Float(4, 1.5f, 10, 8));
                g2.draw(new Ellipse2D.Float(3, 10, 2.5f, 2.5f));
                g2.draw(new Ellipse2D.Float(1, 13, 1.5f, 1.5f));
            }
        };
    }
}

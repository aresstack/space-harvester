package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Graphics2D;

/** Gezeichnete Symbole der Startseite und der fünf Arbeitsbereiche des Command Deck (keine Schriftzeichen, siehe Glyph). */
final class DeckIcons {

    private DeckIcons() {
    }

    /** Häuschen: die Startseite des Deck, von der aus nichts verbindet. */
    static StrokeIcon home() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(1, 8, 8, 1);
                g2.drawLine(8, 1, 15, 8);
                g2.drawLine(3, 6, 3, 14);
                g2.drawLine(13, 6, 13, 14);
                g2.drawLine(3, 14, 13, 14);
                g2.drawRect(6, 9, 4, 5);
            }
        };
    }

    /** Bildschirm mit Eingabeaufforderung „›_“. */
    static StrokeIcon terminal() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(1, 2, 14, 11, 3, 3);
                g2.drawLine(4, 6, 6, 8);
                g2.drawLine(6, 8, 4, 10);
                g2.drawLine(8, 10, 11, 10);
            }
        };
    }

    /** Zwei Pfeile gegenläufig: hoch- und herunterladen. */
    static StrokeIcon ftp() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(5, 13, 5, 3);
                g2.drawLine(2, 6, 5, 3);
                g2.drawLine(8, 6, 5, 3);
                g2.drawLine(11, 3, 11, 13);
                g2.drawLine(8, 10, 11, 13);
                g2.drawLine(14, 10, 11, 13);
            }
        };
    }

    /** Geschweifte Klammern: Natural-Quelltext auf dem Entwicklungsserver. */
    static StrokeIcon ndv() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawArc(3, 2, 4, 6, 90, 90);
                g2.drawArc(1, 5, 4, 3, 270, 90);
                g2.drawArc(1, 8, 4, 3, 0, 90);
                g2.drawArc(3, 8, 4, 6, 180, 90);
                g2.drawArc(9, 2, 4, 6, 0, 90);
                g2.drawArc(11, 5, 4, 3, 180, 90);
                g2.drawArc(11, 8, 4, 3, 90, 90);
                g2.drawArc(9, 8, 4, 6, 270, 90);
            }
        };
    }

    /** Beleg mit Zeilen und Lupe: Dokumentserver BetaView. */
    static StrokeIcon betaView() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRect(2, 1, 9, 13);
                g2.drawLine(4, 4, 9, 4);
                g2.drawLine(4, 7, 9, 7);
                g2.drawLine(4, 10, 6, 10);
                g2.drawOval(8, 8, 4, 4);
                g2.drawLine(12, 12, 15, 15);
            }
        };
    }

    /** Ordner: lokale Dateien, SharePoint, weitere Dateisysteme. */
    static StrokeIcon files() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(1, 3, 6, 3);
                g2.drawLine(6, 3, 8, 5);
                g2.drawLine(8, 5, 15, 5);
                g2.drawRoundRect(1, 3, 14, 11, 2, 2);
            }
        };
    }
}

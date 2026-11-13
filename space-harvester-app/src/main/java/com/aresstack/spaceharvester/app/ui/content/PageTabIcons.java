package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import javax.swing.Icon;
import java.awt.Graphics2D;

/**
 * Symbole der Seitenreiter in der Werkzeugleiste unten (der Name steht im Tooltip): wo es passt, die Glyphen des
 * Comic-Baukastens, sonst eigene im selben Strich.
 */
final class PageTabIcons {

    private PageTabIcons() {
    }

    /** Leseansicht: das aufgeschlagene Buch. */
    static Icon reading() {
        return ComposerIcons.openBook();
    }

    /** Text: das Blatt mit Zeilen. */
    static Icon text() {
        return ComposerIcons.document();
    }

    /** Eigenschaften: das Info-Zeichen. */
    static Icon properties() {
        return ComposerIcons.info();
    }

    /** Anhänge: die Büroklammer. */
    static Icon attachments() {
        return ComposerIcons.paperclip();
    }

    /** Analysen: drei Balken über einer Grundlinie. */
    static Icon analyses() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 13, 13, 13);
                g2.drawLine(4, 11, 4, 8);
                g2.drawLine(7, 11, 7, 3);
                g2.drawLine(11, 11, 11, 6);
            }
        };
    }

    /** Export: ein Fach mit Pfeil heraus nach oben (Ausgabe in den Ausgabeordner). */
    static Icon export() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {2, 2, 13, 13}, new int[] {9, 13, 13, 9}, 4);
                g2.drawLine(7, 2, 7, 10);
                g2.drawPolyline(new int[] {4, 7, 10}, new int[] {5, 2, 5}, 3);
            }
        };
    }

    /** Erweitert: spitze Klammern mit Schrägstrich (Quelltext, technische Angaben). */
    static Icon advanced() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {5, 1, 5}, new int[] {3, 7, 11}, 3);
                g2.drawPolyline(new int[] {10, 14, 10}, new int[] {3, 7, 11}, 3);
                g2.drawLine(9, 2, 6, 12);
            }
        };
    }
}

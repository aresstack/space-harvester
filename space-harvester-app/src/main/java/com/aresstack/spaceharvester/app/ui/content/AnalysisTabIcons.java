package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import com.aresstack.spaceharvester.app.ui.Glyph;

import javax.swing.Icon;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * Symbole der Reiter in den Analysen einer Auswahl (Angelo 27.11.2026: Symbole statt Text, der Name steht im
 * Tooltip). Gezeichnet im Strich der übrigen Reitersymbole ({@link PageTabIcons}); wo es schon ein Symbol gibt, wird
 * es genommen.
 */
final class AnalysisTabIcons {

    private AnalysisTabIcons() {
    }

    /** Begriffe: ein Etikett mit Loch, wie ein Schild an einem Wort. */
    static Icon terms() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D tag = new Path2D.Double();
                tag.moveTo(2, 2);
                tag.lineTo(8, 2);
                tag.lineTo(13.5, 7.5);
                tag.lineTo(7.5, 13.5);
                tag.lineTo(2, 8);
                tag.closePath();
                g2.draw(tag);
                g2.drawOval(4, 4, 2, 2);
            }
        };
    }

    /** Aussagen: eine Sprechblase mit zwei Zeilen. */
    static Icon statements() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(1, 2, 13, 9, 3, 3);
                g2.drawPolyline(new int[] {4, 4, 7}, new int[] {11, 14, 11}, 3);
                g2.drawLine(4, 5, 11, 5);
                g2.drawLine(4, 8, 9, 8);
            }
        };
    }

    /** Zuerst lesen: ein Lesezeichen-Band, verwandt mit dem Stern der Favoriten, aber nicht derselbe. */
    static Icon readFirst() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {4, 4, 11, 11, 7, 4}, new int[] {13, 2, 2, 13, 10, 13}, 6);
            }
        };
    }

    /** Mögliche Widersprüche: der Blitz. */
    static Icon conflicts() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {9, 3, 7, 5, 12, 8, 9}, new int[] {1, 8, 8, 14, 6, 6, 1}, 7);
            }
        };
    }

    /** Fachglossar: ein geschlossenes Buch mit Rücken und zwei Zeilen. */
    static Icon glossary() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(2, 1, 11, 13, 2, 2);
                g2.drawLine(5, 1, 5, 14);
                g2.drawLine(7, 5, 11, 5);
                g2.drawLine(7, 8, 11, 8);
            }
        };
    }

    /** Graph: die verbundenen Knoten, wie überall für den Graphen. */
    static Icon graph() {
        return Glyph.GRAPH;
    }

    /** Beziehungen: der Doppelpfeil nach links und rechts. */
    static Icon relations() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(1, 7, 14, 7);
                g2.drawPolyline(new int[] {4, 1, 4}, new int[] {4, 7, 10}, 3);
                g2.drawPolyline(new int[] {11, 14, 11}, new int[] {4, 7, 10}, 3);
            }
        };
    }

    /** Absätze: vier Zeilen, die letzte kürzer. */
    static Icon passages() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 3, 13, 3);
                g2.drawLine(2, 6, 13, 6);
                g2.drawLine(2, 9, 13, 9);
                g2.drawLine(2, 12, 8, 12);
            }
        };
    }

    /** Verweise: zwei Kettenglieder. */
    static Icon references() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(1, 6, 7, 4, 4, 4);
                g2.drawRoundRect(7, 6, 7, 4, 4, 4);
            }
        };
    }

    /** Bilder: ein Rahmen mit Berg und Sonne. */
    static Icon images() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(2, 3, 11, 10, 2, 2);
                g2.drawOval(4, 5, 2, 2);
                g2.drawPolyline(new int[] {3, 7, 9, 12}, new int[] {12, 8, 10, 7}, 4);
            }
        };
    }
}

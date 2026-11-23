package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Graphics2D;

/** Strichsymbole der Werkzeugleiste des Editors (16 × 16, Vordergrundfarbe des Knopfs). */
public final class EditorIcons {

    private EditorIcons() {
    }

    /** Gliederung: drei eingerückte Zeilen mit Punkten. */
    public static StrokeIcon outline() {
        return icon(g -> {
            g.fillRect(2, 3, 2, 2);
            g.drawLine(6, 4, 14, 4);
            g.fillRect(5, 7, 2, 2);
            g.drawLine(9, 8, 14, 8);
            g.fillRect(5, 11, 2, 2);
            g.drawLine(9, 12, 14, 12);
        });
    }

    /** Vergleichen: zwei Spalten nebeneinander. */
    public static StrokeIcon compare() {
        return icon(g -> {
            g.drawRect(1, 2, 6, 12);
            g.drawRect(9, 2, 6, 12);
            g.drawLine(3, 6, 5, 6);
            g.drawLine(11, 6, 13, 6);
            g.drawLine(3, 9, 5, 9);
            g.drawLine(11, 10, 13, 10);
        });
    }

    /** Zeilenumbruch: Zeile mit Pfeil zurück. */
    public static StrokeIcon wrap() {
        return icon(g -> {
            g.drawLine(2, 3, 14, 3);
            g.drawLine(2, 7, 12, 7);
            g.drawArc(9, 7, 5, 5, -90, 180);
            g.drawLine(11, 12, 6, 12);
            g.drawLine(6, 12, 8, 10);
            g.drawLine(6, 12, 8, 14);
            g.drawLine(2, 12, 3, 12);
        });
    }

    /** Rückgängig: gebogener Pfeil nach links. */
    public static StrokeIcon undo() {
        return icon(g -> {
            g.drawArc(4, 4, 10, 9, -90, 180);
            g.drawLine(9, 4, 3, 4);
            g.drawLine(3, 4, 6, 1);
            g.drawLine(3, 4, 6, 7);
            g.drawLine(9, 13, 6, 13);
        });
    }

    /** Wiederholen: gebogener Pfeil nach rechts. */
    public static StrokeIcon redo() {
        return icon(g -> {
            g.drawArc(2, 4, 10, 9, 90, 180);
            g.drawLine(7, 4, 13, 4);
            g.drawLine(13, 4, 10, 1);
            g.drawLine(13, 4, 10, 7);
            g.drawLine(7, 13, 10, 13);
        });
    }

    /** Kopieren: zwei Blätter. */
    public static StrokeIcon copy() {
        return icon(g -> {
            g.drawRect(5, 5, 9, 10);
            g.drawLine(2, 11, 2, 1);
            g.drawLine(2, 1, 10, 1);
            g.drawLine(10, 1, 10, 3);
        });
    }

    /** Speichern unter: Diskette mit Stift. */
    public static StrokeIcon saveAs() {
        return icon(g -> {
            g.drawLine(2, 2, 10, 2);
            g.drawLine(10, 2, 12, 4);
            g.drawLine(12, 4, 12, 7);
            g.drawLine(2, 2, 2, 14);
            g.drawLine(2, 14, 7, 14);
            g.drawRect(4, 2, 5, 3);
            g.drawLine(9, 14, 15, 8);
            g.drawLine(9, 14, 9, 12);
            g.drawLine(9, 14, 11, 14);
        });
    }

    /** Zeilen filtern: Trichter über Zeilen. */
    public static StrokeIcon filterLines() {
        return icon(g -> {
            g.drawLine(1, 2, 11, 2);
            g.drawLine(1, 2, 5, 7);
            g.drawLine(11, 2, 7, 7);
            g.drawLine(5, 7, 5, 12);
            g.drawLine(7, 7, 7, 10);
            g.drawLine(9, 10, 15, 10);
            g.drawLine(9, 13, 15, 13);
        });
    }

    /** Beziehungen: drei verbundene Knoten. */
    public static StrokeIcon relations() {
        return icon(g -> {
            g.drawOval(1, 6, 4, 4);
            g.drawOval(11, 1, 4, 4);
            g.drawOval(11, 11, 4, 4);
            g.drawLine(5, 7, 11, 4);
            g.drawLine(5, 9, 11, 12);
        });
    }

    /** Nach Art gruppieren: Ordner. */
    public static StrokeIcon group() {
        return icon(g -> {
            g.drawLine(1, 4, 6, 4);
            g.drawLine(6, 4, 8, 6);
            g.drawRect(1, 6, 14, 8);
        });
    }

    private interface Painter {
        void paint(Graphics2D g);
    }

    private static StrokeIcon icon(Painter painter) {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                painter.paint(g2);
            }
        };
    }
}

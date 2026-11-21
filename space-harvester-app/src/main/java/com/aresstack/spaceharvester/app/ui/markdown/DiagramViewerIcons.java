package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import javax.swing.Icon;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * Strichsymbole der Diagrammbetrachter (Verkleinern, Vergrößern, Einpassen, Kopieren, Speichern) statt
 * Unicode-/Emoji-Zeichen, die je nach Schrift fehlen oder bunt erscheinen. Jedes Symbol zeichnet in der
 * Vordergrundfarbe seines Knopfs.
 */
final class DiagramViewerIcons {

    private static final int SIZE = 16;

    private DiagramViewerIcons() {
    }

    static Icon zoomOut() {
        return new StrokeIcon(SIZE, SIZE) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(3, 8, 13, 8);
            }
        };
    }

    static Icon zoomIn() {
        return new StrokeIcon(SIZE, SIZE) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(3, 8, 13, 8);
                g2.drawLine(8, 3, 8, 13);
            }
        };
    }

    /** Vier Ecken: das Diagramm wird in den Rahmen eingepasst. */
    static Icon fit() {
        return new StrokeIcon(SIZE, SIZE) {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D corners = new Path2D.Double();
                corners.moveTo(2, 6);
                corners.lineTo(2, 2);
                corners.lineTo(6, 2);
                corners.moveTo(10, 2);
                corners.lineTo(14, 2);
                corners.lineTo(14, 6);
                corners.moveTo(14, 10);
                corners.lineTo(14, 14);
                corners.lineTo(10, 14);
                corners.moveTo(6, 14);
                corners.lineTo(2, 14);
                corners.lineTo(2, 10);
                g2.draw(corners);
            }
        };
    }

    /** Zwei versetzte Blätter. */
    static Icon copy() {
        return new StrokeIcon(SIZE, SIZE) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(5, 5, 9, 9, 3, 3);
                Path2D back = new Path2D.Double();
                back.moveTo(3, 11);
                back.lineTo(2, 11);
                back.lineTo(2, 2);
                back.lineTo(11, 2);
                back.lineTo(11, 3);
                g2.draw(back);
            }
        };
    }

    /** Pfeil nach unten in eine Ablage. */
    static Icon save() {
        return new StrokeIcon(SIZE, SIZE) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(8, 2, 8, 10);
                Path2D head = new Path2D.Double();
                head.moveTo(4.5, 6.5);
                head.lineTo(8, 10);
                head.lineTo(11.5, 6.5);
                g2.draw(head);
                Path2D tray = new Path2D.Double();
                tray.moveTo(2, 10);
                tray.lineTo(2, 14);
                tray.lineTo(14, 14);
                tray.lineTo(14, 10);
                g2.draw(tray);
            }
        };
    }
}

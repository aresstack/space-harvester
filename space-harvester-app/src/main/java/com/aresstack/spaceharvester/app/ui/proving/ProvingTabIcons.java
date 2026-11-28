package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import javax.swing.Icon;

/**
 * Symbole der Ansichten im Proving Ground (Angelo 28.11.2026: Symbolknöpfe unten in der Fußleiste wie bei den
 * Analysen, der Name steht im Tooltip). Gezeichnet im Strich der übrigen Reitersymbole.
 */
final class ProvingTabIcons {

    private ProvingTabIcons() {
    }

    /** Schritte: eine nummerierte Liste, drei Punkte mit je einer Zeile. */
    static Icon steps() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                for (int y = 3; y <= 13; y += 5) {
                    g2.drawOval(1, y - 1, 2, 2);
                    g2.drawLine(6, y, 14, y);
                }
            }
        };
    }

    /** Hooks: ein Haken an einer Öse. */
    static Icon hooks() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(6, 1, 3, 3);
                g2.drawLine(8, 4, 8, 10);
                g2.draw(new Arc2D.Double(3, 7, 5, 6, 180, 180, Arc2D.OPEN));
                g2.drawLine(3, 10, 3, 8);
            }
        };
    }

    /** Vorbedingungen: ein Kästchen mit Haken vor einer Zeile, darunter ein leeres. */
    static Icon preconditions() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRect(1, 2, 4, 4);
                g2.drawPolyline(new int[] {2, 3, 6}, new int[] {4, 5, 1}, 3);
                g2.drawLine(8, 4, 14, 4);
                g2.drawRect(1, 9, 4, 4);
                g2.drawLine(8, 11, 14, 11);
            }
        };
    }

    /** Benutzer: Kopf und Schultern. */
    static Icon users() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(5, 1, 5, 5);
                g2.draw(new Arc2D.Double(2, 8, 11, 10, 0, 180, Arc2D.OPEN));
            }
        };
    }
}

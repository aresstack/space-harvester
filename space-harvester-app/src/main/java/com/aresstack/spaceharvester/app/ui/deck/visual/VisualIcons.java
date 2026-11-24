package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;

import java.awt.Graphics2D;

/** Strichsymbole der Diagramm-Ansicht (16 × 16, Vordergrundfarbe des Knopfs). */
public final class VisualIcons {

    private VisualIcons() {
    }

    /** „Visuell“: Auge (in MainframeMate der Knopf „👁 Visuell“). */
    public static StrokeIcon eye() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g) {
                g.drawArc(1, 4, 14, 12, 20, 140);
                g.drawArc(1, 0, 14, 12, 200, 140);
                g.drawOval(5, 5, 6, 6);
                g.fillOval(7, 7, 2, 2);
            }
        };
    }

    /** „Verkleinern“: ein Minus, Gegenstück zum Plus. */
    static StrokeIcon minus() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g) {
                g.drawLine(3, 8, 13, 8);
            }
        };
    }

    /**
     * Diagrammtyp als Strichsymbol statt Emoji (die Emoji zeigt Swing unter Windows als leere Kästchen): Struktur =
     * Baum, Ablauf = verzweigte Pfeile, Sequenz = Hin- und Rückpfeil, Mindmap = Mitte mit Ästen, ER = Tabelle.
     */
    static StrokeIcon diagramType(DiagramType type) {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g) {
                switch (type) {
                    case STRUCTURE -> {
                        g.drawRect(6, 1, 4, 3);
                        g.drawRect(1, 11, 4, 3);
                        g.drawRect(11, 11, 4, 3);
                        g.drawLine(8, 4, 8, 8);
                        g.drawLine(3, 8, 13, 8);
                        g.drawLine(3, 8, 3, 11);
                        g.drawLine(13, 8, 13, 11);
                    }
                    case FLOWCHART -> {
                        g.drawLine(8, 1, 8, 5);
                        g.drawPolygon(new int[] {8, 13, 8, 3}, new int[] {5, 8, 11, 8}, 4);
                        g.drawLine(3, 8, 1, 8);
                        g.drawLine(1, 8, 1, 14);
                        g.drawLine(13, 8, 15, 8);
                        g.drawLine(15, 8, 15, 14);
                        g.drawLine(8, 11, 8, 15);
                    }
                    case SEQUENCE -> {
                        g.drawLine(2, 5, 14, 5);
                        g.drawLine(14, 5, 11, 2);
                        g.drawLine(14, 5, 11, 8);
                        g.drawLine(2, 11, 14, 11);
                        g.drawLine(2, 11, 5, 8);
                        g.drawLine(2, 11, 5, 14);
                    }
                    case MINDMAP -> {
                        g.drawOval(5, 6, 6, 4);
                        g.drawLine(5, 8, 2, 3);
                        g.drawLine(5, 8, 2, 13);
                        g.drawLine(11, 8, 14, 3);
                        g.drawLine(11, 8, 14, 13);
                        g.fillOval(1, 2, 2, 2);
                        g.fillOval(1, 12, 2, 2);
                        g.fillOval(13, 2, 2, 2);
                        g.fillOval(13, 12, 2, 2);
                    }
                    default -> {
                        g.drawRect(1, 3, 6, 10);
                        g.drawLine(1, 6, 7, 6);
                        g.drawRect(10, 3, 5, 7);
                        g.drawLine(10, 6, 15, 6);
                        g.drawLine(7, 8, 10, 8);
                    }
                }
            }
        };
    }
}

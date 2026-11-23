package com.aresstack.spaceharvester.app.ui.deck.visual;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import java.awt.Graphics2D;

/** Strichsymbole der Diagramm-Ansicht (16 × 16, Vordergrundfarbe des Knopfs). */
final class VisualIcons {

    private VisualIcons() {
    }

    /** „Visuell“: Auge (in MainframeMate der Knopf „👁 Visuell“). */
    static StrokeIcon eye() {
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
}

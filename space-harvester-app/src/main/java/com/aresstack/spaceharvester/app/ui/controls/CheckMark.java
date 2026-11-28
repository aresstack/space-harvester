package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * Der Haken vor dem gewählten Eintrag eines Menüs, gezeichnet statt als Textzeichen: Ein „✓“ im Text zeigt sich als
 * leeres Kästchen, wenn die Schrift des Eintrags das Zeichen nicht hat (Angelo 28.11.2026, Verlauf der Chats). Die
 * nicht gewählten Einträge tragen dasselbe Symbol leer, damit alle Titel in einer Flucht stehen.
 */
public final class CheckMark implements Icon {

    private static final int SIZE = 14;
    private static final CheckMark CHECKED = new CheckMark(true);
    private static final CheckMark BLANK = new CheckMark(false);

    private final boolean checked;

    private CheckMark(boolean checked) {
        this.checked = checked;
    }

    /** Der Haken, oder für nicht gewählte Einträge sein leerer Platz. */
    public static CheckMark of(boolean checked) {
        return checked ? CHECKED : BLANK;
    }

    public boolean checked() {
        return checked;
    }

    @Override
    public void paintIcon(Component host, Graphics graphics, int x, int y) {
        if (!checked) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(host.getForeground());
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float tick = new Path2D.Float();
            tick.moveTo(x + 2.5f, y + 7.5f);
            tick.lineTo(x + 5.5f, y + 10.5f);
            tick.lineTo(x + 11.5f, y + 3.5f);
            g.draw(tick);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}

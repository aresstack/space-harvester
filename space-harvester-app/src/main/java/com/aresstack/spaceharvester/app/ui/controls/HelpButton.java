package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

/**
 * Das runde Fragezeichen neben dem ✕ einer Kopfzeile (Vorbild: Hilfe-Knopf in askai und MainframeMate), im Stil
 * des Schließen-Chips dieser App: weißer Kreis mit Tintenrand und fettem „?“, beim Überfahren gelb. Ein Klick öffnet
 * ein {@link InfoPopup} mit Überschrift und Text unter dem Knopf; es bleibt stehen, bis irgendwo daneben geklickt
 * oder Esc gedrückt wird, ein erweiterter Tooltip also.
 */
public final class HelpButton extends JButton {

    private final int size;
    private final InfoPopup popup = new InfoPopup();

    /**
     * @param size    Kantenlänge wie beim ✕ daneben
     * @param heading Überschrift des Hilfefensters
     * @param text    der Hilfetext, bricht um; Absätze mit Leerzeilen
     */
    public HelpButton(int size, String heading, String text) {
        this.size = Math.max(16, size);
        popup.text(heading, text);
        setToolTipText("Hilfe");
        getAccessibleContext().setAccessibleName("Hilfe: " + heading);
        setFocusable(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRolloverEnabled(true);
        addActionListener(e -> popup.showAt(this, 0, getHeight()));
    }

    /** Das Hilfefenster, für Tests. */
    public InfoPopup popup() {
        return popup;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(size, size);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Derselbe Kreis wie beim ✕ (ComicWindowCloseButton): 2 px eingerückt, Durchmesser Größe minus 5.
            float diameter = Math.min(getWidth(), getHeight()) - 5f;
            Ellipse2D chip = new Ellipse2D.Float(2f, 2f, diameter, diameter);
            boolean hot = getModel().isRollover() || getModel().isPressed() || popup.isVisible();
            g2.setColor(hot ? Comic.YELLOW : Color.WHITE);
            g2.fill(chip);
            g2.setColor(Comic.INK);
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(chip);
            g2.setFont(Comic.semiBold(Math.max(12f, diameter * 0.62f)).deriveFont(Font.BOLD));
            FontMetrics metrics = g2.getFontMetrics();
            float center = 2f + diameter / 2f;
            g2.drawString("?", Math.round(center - metrics.stringWidth("?") / 2f),
                    Math.round(center - metrics.getHeight() / 2f + metrics.getAscent()));
        } finally {
            g2.dispose();
        }
    }

    /** Klicks zählen nur im Kreis. */
    @Override
    public boolean contains(int x, int y) {
        float radius = (Math.min(getWidth(), getHeight()) - 5f) / 2f;
        float dx = x - (2f + radius);
        float dy = y - (2f + radius);
        return dx * dx + dy * dy <= radius * radius;
    }
}

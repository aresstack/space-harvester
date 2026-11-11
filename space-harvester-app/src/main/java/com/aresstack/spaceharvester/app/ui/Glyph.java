package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Kleine Vektorsymbole für Icon-Knöpfe (16 × 16, skaliert mit der Bildschirmauflösung): Aktualisieren, Stift,
 * Papierkorb, Schlüssel, Mehr, Aufklappen (Pfeil rechts), Zuklappen (Pfeil unten),
 * Übernehmen (Haken), Verwerfen (Kreuz),
 * Alles zuklappen (Minus im Kasten), Fadenkreuz (aktuelles Dokument zeigen), Einpassen (Ecken), Tabelle (Raster),
 * Graph (verbundene Knoten), Filter (Trichter), Zurück (Pfeil links), Nachbarn (Knoten mit Plus). Gemalt in der Vordergrundfarbe der Komponente, damit Hover und deaktiviert stimmen.
 */
public enum Glyph implements Icon {

    REFRESH, PENCIL, TRASH, KEY, MORE, EXPAND, COLLAPSE, ACCEPT, CANCEL, EXPORT, COLLAPSE_ALL, CROSSHAIR, FIT, TABLE,
    GRAPH, FILTER, BACK, NEIGHBOURS, BROOM, BOUNCE;

    private static final int SIZE = 16;

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            Color color = c == null ? Comic.INK : c.isEnabled() ? c.getForeground() : Comic.LINE;
            g.setColor(color);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (this) {
                case REFRESH -> {
                    g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 70, 280, Arc2D.OPEN));
                    Path2D head = new Path2D.Double();
                    head.moveTo(9.2, 0.8);
                    head.lineTo(12.4, 3.2);
                    head.lineTo(8.8, 5.2);
                    g.draw(head);
                }
                case PENCIL -> {
                    Path2D body = new Path2D.Double();
                    body.moveTo(3, 13);
                    body.lineTo(3.6, 10);
                    body.lineTo(11, 2.6);
                    body.lineTo(13.4, 5);
                    body.lineTo(6, 12.4);
                    body.closePath();
                    g.draw(body);
                    g.draw(new Line2D.Double(9.4, 4.2, 11.8, 6.6));
                }
                case TRASH -> {
                    g.draw(new Line2D.Double(2.5, 4, 13.5, 4));
                    g.draw(new Line2D.Double(6.2, 4, 6.8, 2.2));
                    g.draw(new Line2D.Double(9.8, 4, 9.2, 2.2));
                    g.draw(new Line2D.Double(6.8, 2.2, 9.2, 2.2));
                    g.draw(new RoundRectangle2D.Double(4, 4, 8, 10, 2, 2));
                    g.draw(new Line2D.Double(6.6, 6.8, 6.6, 11.4));
                    g.draw(new Line2D.Double(9.4, 6.8, 9.4, 11.4));
                }
                case KEY -> {
                    g.draw(new Ellipse2D.Double(1.8, 5, 6, 6));
                    g.draw(new Line2D.Double(7.8, 8, 14, 8));
                    g.draw(new Line2D.Double(11.5, 8, 11.5, 10.6));
                    g.draw(new Line2D.Double(13.6, 8, 13.6, 10));
                }
                case EXPAND -> {
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(6, 4);
                    arrow.lineTo(10.5, 8);
                    arrow.lineTo(6, 12);
                    g.draw(arrow);
                }
                case COLLAPSE -> {
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(4, 6);
                    arrow.lineTo(8, 10.5);
                    arrow.lineTo(12, 6);
                    g.draw(arrow);
                }
                case ACCEPT -> {
                    Path2D tick = new Path2D.Double();
                    tick.moveTo(3, 8.5);
                    tick.lineTo(6.5, 12);
                    tick.lineTo(13, 4.5);
                    g.draw(tick);
                }
                case CANCEL -> {
                    g.draw(new Line2D.Double(4, 4, 12, 12));
                    g.draw(new Line2D.Double(12, 4, 4, 12));
                }
                case EXPORT -> {
                    // Ablage mit Pfeil nach oben: etwas verlässt die App.
                    Path2D tray = new Path2D.Double();
                    tray.moveTo(2.5, 9.5);
                    tray.lineTo(2.5, 13.5);
                    tray.lineTo(13.5, 13.5);
                    tray.lineTo(13.5, 9.5);
                    g.draw(tray);
                    g.draw(new Line2D.Double(8, 10.5, 8, 2.2));
                    Path2D head = new Path2D.Double();
                    head.moveTo(5, 5.2);
                    head.lineTo(8, 2.2);
                    head.lineTo(11, 5.2);
                    g.draw(head);
                }
                case COLLAPSE_ALL -> {
                    g.draw(new RoundRectangle2D.Double(2.5, 2.5, 11, 11, 3, 3));
                    g.draw(new Line2D.Double(5.2, 8, 10.8, 8));
                }
                case CROSSHAIR -> {
                    g.draw(new Ellipse2D.Double(3.5, 3.5, 9, 9));
                    g.fill(new Ellipse2D.Double(6.6, 6.6, 2.8, 2.8));
                    g.draw(new Line2D.Double(8, 0.8, 8, 3.5));
                    g.draw(new Line2D.Double(8, 12.5, 8, 15.2));
                    g.draw(new Line2D.Double(0.8, 8, 3.5, 8));
                    g.draw(new Line2D.Double(12.5, 8, 15.2, 8));
                }
                case FIT -> {
                    // Vier Ecken eines Rahmens: alles in die Fläche einpassen.
                    for (int[] k : new int[][] {{2, 2, 1, 1}, {14, 2, -1, 1}, {2, 14, 1, -1}, {14, 14, -1, -1}}) {
                        g.draw(new Line2D.Double(k[0], k[1], k[0] + 4 * k[2], k[1]));
                        g.draw(new Line2D.Double(k[0], k[1], k[0], k[1] + 4 * k[3]));
                    }
                }
                case TABLE -> {
                    g.draw(new RoundRectangle2D.Double(2, 2.5, 12, 11, 2, 2));
                    g.draw(new Line2D.Double(2, 6.2, 14, 6.2));
                    g.draw(new Line2D.Double(2, 9.8, 14, 9.8));
                    g.draw(new Line2D.Double(7, 2.5, 7, 13.5));
                }
                case GRAPH -> {
                    g.draw(new Line2D.Double(4.5, 4.5, 11.5, 11.5));
                    g.draw(new Line2D.Double(4.5, 4.5, 11.5, 4.5));
                    g.fill(new Ellipse2D.Double(2.2, 2.2, 4.6, 4.6));
                    g.fill(new Ellipse2D.Double(9.2, 2.2, 4.6, 4.6));
                    g.fill(new Ellipse2D.Double(9.2, 9.2, 4.6, 4.6));
                }
                case FILTER -> {
                    Path2D funnel = new Path2D.Double();
                    funnel.moveTo(2, 3);
                    funnel.lineTo(14, 3);
                    funnel.lineTo(9.4, 8.6);
                    funnel.lineTo(9.4, 13.4);
                    funnel.lineTo(6.6, 11.8);
                    funnel.lineTo(6.6, 8.6);
                    funnel.closePath();
                    g.draw(funnel);
                }
                case BACK -> {
                    g.draw(new Line2D.Double(3, 8, 13.5, 8));
                    Path2D head = new Path2D.Double();
                    head.moveTo(7, 4);
                    head.lineTo(3, 8);
                    head.lineTo(7, 12);
                    g.draw(head);
                }
                case NEIGHBOURS -> {
                    // Ein Knoten, dessen Nachbarn dazukommen: Kreis mit Plus.
                    g.fill(new Ellipse2D.Double(1.8, 5.8, 4.4, 4.4));
                    g.draw(new Line2D.Double(6, 8, 9.2, 8));
                    g.draw(new Ellipse2D.Double(8.6, 3.6, 7, 7));
                    g.draw(new Line2D.Double(12.1, 5.4, 12.1, 8.8));
                    g.draw(new Line2D.Double(10.4, 7.1, 13.8, 7.1));
                }
                case BOUNCE -> {
                    // Abprallender Pfeil (Angelo 11.11.2026): schräg auf eine Kante, von dort zurück nach oben.
                    g.draw(new Line2D.Double(1.5, 14, 14.5, 14));
                    Path2D path = new Path2D.Double();
                    path.moveTo(2, 3);
                    path.lineTo(7.5, 12);
                    path.lineTo(13.5, 3.5);
                    g.draw(path);
                    Path2D head = new Path2D.Double();
                    head.moveTo(10.2, 3.2);
                    head.lineTo(13.5, 3.5);
                    head.lineTo(13.8, 6.8);
                    g.draw(head);
                }
                case BROOM -> {
                    // Stiel schräg von oben rechts, unten ein Fächer aus Borsten.
                    g.draw(new Line2D.Double(13.8, 1.6, 8.2, 7.8));
                    Path2D head = new Path2D.Double();
                    head.moveTo(6.4, 6.4);
                    head.lineTo(9.6, 9.6);
                    head.lineTo(6.2, 14.6);
                    head.lineTo(1.4, 9.8);
                    head.closePath();
                    g.draw(head);
                    g.draw(new Line2D.Double(7.9, 8.0, 3.8, 12.2));
                }
                case MORE -> {
                    for (double cx : new double[] {3, 8, 13}) {
                        g.fill(new Ellipse2D.Double(cx - 1.4, 6.6, 2.8, 2.8));
                    }
                }
            }
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

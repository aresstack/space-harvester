package com.aresstack.enterpriseai.ui.comic.paint;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Die Symbole der Drawer-Reiter (statt der Beschriftungen): die Schriftrolle für „Chats“, der Chemiekolben aus
 * askai-java8 (arch, {@code ResearchPhaseToolbarContribution.FlaskIcon}) in Blau für „Wissensquellen“ und der gelbe
 * Stern (MainframeMates ★ im Reiterkopf) für „Favoriten“, dazu der rote Kompass für „Navigation“. Kolben, Stern und
 * Kompass behalten ihre Farbe in jedem Zustand; die Rolle hat weißes Papier und zeichnet ihre Linien in der Vordergrundfarbe des Knopfs (Tinte in Ruhe, weiß auf
 * der dunklen Platte des aktiven Reiters).
 */
public final class SidebarIcons {

    private static final float SCROLL_GRID = 24f;
    private static final float SCROLL_STROKE = 1.6f;

    private SidebarIcons() {
    }

    /**
     * Die Schriftrolle (24×24-Raster, Strich 1,6, runde Enden): obere und untere Rolle als Kapseln mit dem
     * Rollenquerschnitt rechts oben und links unten, dazwischen die Pergamentbahn mit drei Textzeilen.
     */
    public static StrokeIcon scroll() {
        return new StrokeIcon(20, 20) {
            @Override
            protected void paint(Graphics2D g2) {
                Color ink = g2.getColor();
                g2.scale(getIconWidth() / SCROLL_GRID, getIconHeight() / SCROLL_GRID);
                g2.setStroke(new BasicStroke(SCROLL_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                RoundRectangle2D top = new RoundRectangle2D.Float(4f, 4f, 16f, 3.2f, 3.2f, 3.2f);
                RoundRectangle2D bottom = new RoundRectangle2D.Float(4f, 16.8f, 16f, 3.2f, 3.2f, 3.2f);
                g2.setColor(ResearchUiPalette.SCROLL_PAPER);
                g2.fill(new Rectangle2D.Float(6.2f, 7.2f, 11.6f, 9.6f));
                g2.fill(top);
                g2.fill(bottom);
                g2.setColor(ink);
                g2.draw(top);
                g2.draw(new Arc2D.Float(16.8f, 4f, 3.2f, 3.2f, 90f, 180f, Arc2D.OPEN));
                g2.draw(new Line2D.Float(6.2f, 7.2f, 6.2f, 16.8f));
                g2.draw(new Line2D.Float(17.8f, 7.2f, 17.8f, 16.8f));
                g2.draw(new Line2D.Float(9f, 10.2f, 15f, 10.2f));
                g2.draw(new Line2D.Float(9f, 12.6f, 15f, 12.6f));
                g2.draw(new Line2D.Float(9f, 15f, 13f, 15f));
                g2.draw(bottom);
                g2.draw(new Arc2D.Float(4f, 16.8f, 3.2f, 3.2f, 90f, -180f, Arc2D.OPEN));
            }
        };
    }

    /** Der Erlenmeyerkolben aus askai arch (16×18, Hals, Schultern, flacher Boden, Rand) in Akzentblau. */
    public static StrokeIcon flask() {
        return new StrokeIcon(16, 18) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.setColor(ResearchUiPalette.ACCENT_BLUE);
                Path2D.Float flask = new Path2D.Float();
                flask.moveTo(6f, 1.5f);
                flask.lineTo(6f, 6.5f);
                flask.lineTo(2f, 15.5f);
                flask.lineTo(14f, 15.5f);
                flask.lineTo(10f, 6.5f);
                flask.lineTo(10f, 1.5f);
                g2.draw(flask);
                g2.drawLine(4, 1, 12, 1); // der Rand
            }
        };
    }

    /** Der gelbe Stern (gefüllt) bzw. sein Umriss für „noch kein Favorit“. */
    public static StrokeIcon star(final boolean filled) {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D.Float star = new Path2D.Float();
                double cx = 8;
                double cy = 8.6;
                for (int i = 0; i < 10; i++) {
                    double radius = i % 2 == 0 ? 7.2 : 3.0;
                    double angle = Math.toRadians(-90 + i * 36);
                    float x = (float) (cx + radius * Math.cos(angle));
                    float y = (float) (cy + radius * Math.sin(angle));
                    if (i == 0) {
                        star.moveTo(x, y);
                    } else {
                        star.lineTo(x, y);
                    }
                }
                star.closePath();
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (filled) {
                    g2.setColor(ResearchUiPalette.FAVORITE_YELLOW);
                    g2.fill(star);
                    g2.setColor(ResearchUiPalette.FAVORITE_YELLOW_EDGE);
                    g2.draw(star);
                } else {
                    g2.setColor(ResearchUiPalette.LIGHT_TEXT_MUTED);
                    g2.draw(star);
                }
            }
        };
    }

    /**
     * The red compass of the tab "Navigation" (issue #97): a ring and a needle as a slim diamond from bottom left to top
     * right with a small hole in its middle, in the size of the star; red in every state like flask and star.
     */
    public static StrokeIcon compass() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.setColor(ResearchUiPalette.COMPASS_RED);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Ellipse2D.Float(1.3f, 1.3f, 13.4f, 13.4f));
                Path2D.Float needle = new Path2D.Float();
                needle.moveTo(11.6f, 4.4f);
                needle.lineTo(9.2f, 9.2f);
                needle.lineTo(4.4f, 11.6f);
                needle.lineTo(6.8f, 6.8f);
                needle.closePath();
                Area body = new Area(needle);
                body.subtract(new Area(new Ellipse2D.Float(7.15f, 7.15f, 1.7f, 1.7f)));
                g2.fill(body);
            }
        };
    }

    /** A page of a source in the navigation tree (issue #97): a sheet with a folded corner, paper light, lines in ink. */
    public static StrokeIcon page() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D.Float sheet = new Path2D.Float();
                sheet.moveTo(3.5f, 1.5f);
                sheet.lineTo(9.5f, 1.5f);
                sheet.lineTo(12.5f, 4.5f);
                sheet.lineTo(12.5f, 14.5f);
                sheet.lineTo(3.5f, 14.5f);
                sheet.closePath();
                g2.setColor(ResearchUiPalette.SCROLL_PAPER);
                g2.fill(sheet);
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(ResearchUiPalette.LIGHT_CONTROL_TEXT);
                g2.draw(sheet);
                g2.draw(new Line2D.Float(9.5f, 1.5f, 9.5f, 4.5f));
                g2.draw(new Line2D.Float(9.5f, 4.5f, 12.5f, 4.5f));
                g2.draw(new Line2D.Float(5.5f, 8f, 10.5f, 8f));
                g2.draw(new Line2D.Float(5.5f, 10.5f, 10.5f, 10.5f));
            }
        };
    }

    /** Der Ordner der Favoriten (MainframeMates Lesezeichen-Ordner): Mappe mit Reiter, Papier hell, Linien in Tinte. */
    public static StrokeIcon folder() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                Path2D.Float folder = new Path2D.Float();
                folder.moveTo(1.5f, 3.5f);
                folder.lineTo(6f, 3.5f);
                folder.lineTo(7.5f, 5f);
                folder.lineTo(14.5f, 5f);
                folder.lineTo(14.5f, 13f);
                folder.lineTo(1.5f, 13f);
                folder.closePath();
                g2.setColor(FOLDER_PAPER);
                g2.fill(folder);
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(ResearchUiPalette.LIGHT_CONTROL_TEXT);
                g2.draw(folder);
                g2.draw(new Line2D.Float(1.5f, 7f, 14.5f, 7f));
            }
        };
    }

    /**
     * The page tree of the navigation footer (#114): a parent node with a stem and two child nodes, in the foreground
     * of the button like the scroll.
     */
    public static StrokeIcon pageTree() {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, 6f, 4f, 1.6f, 1.6f));
                g2.draw(new RoundRectangle2D.Float(8.5f, 6.5f, 6f, 3.5f, 1.6f, 1.6f));
                g2.draw(new RoundRectangle2D.Float(8.5f, 11.5f, 6f, 3.5f, 1.6f, 1.6f));
                Path2D.Float stem = new Path2D.Float();
                stem.moveTo(4.5f, 5.5f);
                stem.lineTo(4.5f, 13.25f);
                stem.lineTo(8.5f, 13.25f);
                stem.moveTo(4.5f, 8.25f);
                stem.lineTo(8.5f, 8.25f);
                g2.draw(stem);
            }
        };
    }

    /** Outgoing references (#114): two chain links with an arrow leaving to the top right. */
    public static StrokeIcon linksOutgoing() {
        return chainWithArrow(true);
    }

    /** Incoming references (#114): the same chain links with an arrow arriving from the top right. */
    public static StrokeIcon linksIncoming() {
        return chainWithArrow(false);
    }

    private static StrokeIcon chainWithArrow(final boolean outgoing) {
        return new StrokeIcon(16, 16) {
            @Override
            protected void paint(Graphics2D g2) {
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                java.awt.geom.AffineTransform plain = g2.getTransform();
                g2.rotate(Math.toRadians(-45), 6f, 10f);
                g2.draw(new RoundRectangle2D.Float(1.2f, 8.2f, 6.2f, 3.6f, 3.6f, 3.6f));
                g2.draw(new RoundRectangle2D.Float(4.6f, 8.2f, 6.2f, 3.6f, 3.6f, 3.6f));
                g2.setTransform(plain);
                Path2D.Float arrow = new Path2D.Float();
                arrow.moveTo(9.5f, 6.5f);
                arrow.lineTo(14.5f, 1.5f);
                if (outgoing) {
                    arrow.moveTo(10.5f, 1.5f);
                    arrow.lineTo(14.5f, 1.5f);
                    arrow.lineTo(14.5f, 5.5f);
                } else {
                    arrow.moveTo(9.5f, 2.5f);
                    arrow.lineTo(9.5f, 6.5f);
                    arrow.lineTo(13.5f, 6.5f);
                }
                g2.draw(arrow);
            }
        };
    }

    private static final Color FOLDER_PAPER = new Color(0xFFF1C2);
}

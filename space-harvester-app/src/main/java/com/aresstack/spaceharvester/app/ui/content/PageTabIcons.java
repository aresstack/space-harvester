package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import javax.swing.Icon;
import java.awt.Graphics2D;

/**
 * Symbole der Seitenreiter in der Werkzeugleiste unten (der Name steht im Tooltip): wo es passt, die Glyphen des
 * Comic-Baukastens, sonst eigene im selben Strich.
 */
final class PageTabIcons {

    private PageTabIcons() {
    }

    /** Leseansicht: das aufgeschlagene Buch. */
    static Icon reading() {
        return ComposerIcons.openBook();
    }

    /**
     * Podcast-Ansicht (bisher „Text“): der Kopfhörer. Der gefilterte Text zum Anhören, mit dem Podcast der Seite
     * (Angelo 23.11.2026: „dann weiß man, dass das so eine gefilterte Ansicht ist, die sich zum Anhören eignet“).
     */
    static Icon text() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawArc(2, 2, 11, 12, 0, 180);
                g2.drawRoundRect(1, 8, 3, 6, 2, 2);
                g2.drawRoundRect(11, 8, 3, 6, 2, 2);
            }
        };
    }

    /** Info (Eigenschaften und Technik der Seite): das Info-Zeichen. */
    static Icon properties() {
        return ComposerIcons.info();
    }

    /** Anhänge: die Büroklammer. */
    static Icon attachments() {
        return ComposerIcons.paperclip();
    }

    /** Analysen: drei Balken über einer Grundlinie. */
    static Icon analyses() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawLine(2, 13, 13, 13);
                g2.drawLine(4, 11, 4, 8);
                g2.drawLine(7, 11, 7, 3);
                g2.drawLine(11, 11, 11, 6);
            }
        };
    }

    /**
     * Export und Schreiben: der Chemiekolben aus dem Enterprise AI Client ({@code SidebarIcons.flask}), unverändert
     * übernommen (Angelo, 27.11.2026: „Das sieht mehr so nach Wissen aus“). Er steht am Reiter „Export“, an den
     * Export-Reitern darin und am Schalter „Schreiben“ im Chat. Knöpfe, die eine Datei herausgeben (Antwort oder
     * Diagramm exportieren, Hochladen), behalten den Pfeil aus dem Fach.
     */
    static Icon export() {
        return com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons.flask();
    }

    /**
     * Derselbe Kolben in der Schriftfarbe des Knopfs statt in seinem Blau: für den eingerasteten Schalter
     * „Schreiben“, dessen Fläche selbst blau ist. Das Symbol wird nicht neu gezeichnet, sondern das des EAC
     * umgefärbt.
     */
    static Icon exportInk() {
        Icon flask = export();
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, java.awt.Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics;
                double scale = Math.max(1.0, g.getTransform().getScaleX());
                int width = (int) Math.ceil(getIconWidth() * scale);
                int height = (int) Math.ceil(getIconHeight() * scale);
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D paint = image.createGraphics();
                try {
                    paint.scale(scale, scale);
                    flask.paintIcon(c, paint, 0, 0);
                    paint.setComposite(java.awt.AlphaComposite.SrcIn);
                    paint.setColor(c == null ? java.awt.Color.WHITE : c.getForeground());
                    paint.fillRect(0, 0, getIconWidth(), getIconHeight());
                } finally {
                    paint.dispose();
                }
                g.drawImage(image, x, y, getIconWidth(), getIconHeight(), null);
            }

            @Override
            public int getIconWidth() {
                return flask.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return flask.getIconHeight();
            }
        };
    }
}

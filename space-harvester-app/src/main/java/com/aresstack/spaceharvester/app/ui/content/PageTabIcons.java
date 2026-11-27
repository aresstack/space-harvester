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
     * Export: der Chemiekolben aus dem Enterprise AI Client ({@code SidebarIcons.flask}), unverändert übernommen.
     * Er steht nur am Knopf „Export“ in der Reiterzeile der Seitenansicht.
     */
    static Icon export() {
        return com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons.flask();
    }
}

package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.IconButton;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Der Chat als Seitenleiste rechts neben der Arbeitsfläche (Angelo, 21.11.2026): oben „Chat: ‹Name› ▾“, rechts davon
 * Neuer Chat, Chat löschen und Einklappen, darunter Verlauf und Eingabe des {@link ChatPanel}. Die Leiste ersetzt den
 * früheren Reiter „Chat“; das Hauptfenster stellt sie neben die Arbeitsfläche und klappt sie auf und zu.
 */
public final class ChatSidebar extends JPanel {

    public static final String TITLE = "Chat";
    public static final String COLLAPSE = "Chat einklappen";
    /** Breite beim ersten Aufklappen. */
    public static final int DEFAULT_WIDTH = 420;
    /** Schmaler wird die offene Leiste nicht: darunter passt die Eingabe nicht mehr. */
    public static final int MIN_WIDTH = 320;

    private final ChatPanel chat;
    private final IconButton collapse = new IconButton(collapseIcon(), COLLAPSE).compact();

    ChatSidebar(ChatPanel chat) {
        super(new BorderLayout());
        this.chat = chat;
        setBackground(Comic.SURFACE);
        JPanel actions = Comic.row(chat.headerActions(), collapse);
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 12, 6, 8)));
        head.add(chat.header(), BorderLayout.CENTER);
        head.add(actions, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);
        add(chat, BorderLayout.CENTER);
        setMinimumSize(new Dimension(MIN_WIDTH, 0));
        setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
    }

    /**
     * Zugeklappt bleibt die Leiste als leere Fläche der Breite null stehen (der Teiler daneben bleibt sichtbar), ihr
     * Inhalt ist unsichtbar, damit die Tabulatortaste nicht in verdeckte Felder läuft.
     */
    public void shown(boolean shown) {
        for (java.awt.Component child : getComponents()) {
            child.setVisible(shown);
        }
    }

    public ChatPanel chat() {
        return chat;
    }

    /** Der Knopf „Chat einklappen“ rechts in der Kopfzeile; was er tut, bestimmt das Hauptfenster. */
    public IconButton collapseButton() {
        return collapse;
    }

    /** Die Sprechblase für den Umschalter des Hauptfensters. */
    public static Icon icon() {
        return ChatIcons.chat();
    }

    /** Einklappen: zwei Winkel nach rechts, wohin die Leiste verschwindet. */
    private static Icon collapseIcon() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {3, 7, 3}, new int[] {4, 8, 12}, 3);
                g2.drawPolyline(new int[] {8, 12, 8}, new int[] {4, 8, 12}, 3);
            }
        };
    }
}

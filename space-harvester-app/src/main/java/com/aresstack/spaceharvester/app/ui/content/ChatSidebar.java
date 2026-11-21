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
    public static final String ENLARGE = "Groß anzeigen";
    public static final String SHRINK = "Wieder als Leiste anzeigen (Esc)";
    /** Breite beim ersten Aufklappen. */
    public static final int DEFAULT_WIDTH = 420;
    /** Schmaler wird die offene Leiste nicht: darunter passt die Eingabe nicht mehr. */
    public static final int MIN_WIDTH = 320;

    private final ChatPanel chat;
    private final IconButton collapse = new IconButton(collapseIcon(), COLLAPSE).compact();
    private final IconButton enlarge = new IconButton(enlargeIcon(false), ENLARGE).compact();
    private Runnable onEscape = () -> { };

    ChatSidebar(ChatPanel chat) {
        super(new BorderLayout());
        this.chat = chat;
        setBackground(Comic.SURFACE);
        JPanel actions = Comic.row(chat.headerActions(), enlarge, collapse);
        // Esc stellt aus der großen Ansicht die Leiste wieder her; Felder mit eigenem Esc (Umbenennen) gehen vor.
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "chat-shrink");
        getActionMap().put("chat-shrink", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                onEscape.run();
            }
        });
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

    /**
     * Der Knopf „Groß anzeigen“ links neben „Chat einklappen“: dieselbe Leiste über die ganze Arbeitsfläche, der
     * Explorer bleibt; was er tut, bestimmt das Hauptfenster.
     */
    public IconButton enlargeButton() {
        return enlarge;
    }

    /** Zeigt am Knopf, ob die Leiste gerade groß ist: Symbol und Tooltip wechseln, Esc gilt nur in der großen Ansicht. */
    public void large(boolean large, Runnable shrink) {
        enlarge.setIcon(enlargeIcon(large));
        enlarge.setToolTipText(large ? SHRINK : ENLARGE);
        enlarge.getAccessibleContext().setAccessibleName(large ? SHRINK : ENLARGE);
        onEscape = large && shrink != null ? shrink : () -> { };
    }

    /** Groß anzeigen: zwei Pfeile in die Ecken; in der großen Ansicht zeigen sie nach innen. */
    private static Icon enlargeIcon(boolean large) {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                if (large) {
                    g2.drawLine(13, 3, 9, 7);
                    g2.drawPolyline(new int[] {9, 9, 12}, new int[] {4, 7, 7}, 3);
                    g2.drawLine(3, 13, 7, 9);
                    g2.drawPolyline(new int[] {4, 7, 7}, new int[] {9, 9, 12}, 3);
                } else {
                    g2.drawLine(9, 7, 13, 3);
                    g2.drawPolyline(new int[] {10, 13, 13}, new int[] {3, 3, 6}, 3);
                    g2.drawLine(7, 9, 3, 13);
                    g2.drawPolyline(new int[] {3, 3, 6}, new int[] {10, 13, 13}, 3);
                }
            }
        };
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

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
 * Neuer Chat, Chat löschen, Groß anzeigen und das Dreieck mit „Seitenleiste einklappen“, darunter Verlauf und Eingabe des {@link ChatPanel}. Die Leiste ersetzt den
 * früheren Reiter „Chat“; das Hauptfenster stellt sie neben die Arbeitsfläche und klappt sie auf und zu.
 */
public final class ChatSidebar extends JPanel {

    public static final String TITLE = "Chat";
    public static final String COLLAPSE = "Seitenleiste einklappen";
    public static final String DELETE_ALL = ChatPanel.DELETE_ALL;
    public static final String MORE = "Weitere Aktionen zum Chat";
    public static final String ENLARGE = "Groß anzeigen";
    public static final String SHRINK = "Wieder als Leiste anzeigen (Esc)";
    /** Breite beim ersten Aufklappen. */
    public static final int DEFAULT_WIDTH = 420;
    /** Schmaler wird die offene Leiste nicht: darunter passt die Eingabe nicht mehr. */
    public static final int MIN_WIDTH = 320;
    /** Höhe der Kopfzeile samt Linie: dieselbe wie die Kopfzeile der Seitenansicht daneben. */
    public static final int HEAD_HEIGHT = com.aresstack.spaceharvester.app.ui.controls.HeadBar.HEIGHT;

    private final ChatPanel chat;
    /**
     * Das Dreieck rechts in der Kopfzeile (Angelo 22.11.2026): Es ersetzt den Knopf „Chat einklappen“; zum Einklappen
     * genügt ein Klick auf den aktiven Knopf oben rechts, der Eintrag im Menü bleibt für alle, die ihn hier suchen.
     */
    private final IconButton more = new IconButton(
            new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(), MORE).compact();
    private final IconButton enlarge = new IconButton(enlargeIcon(false), ENLARGE);
    private Runnable collapse = () -> { };
    private final JPanel switchSlot = new JPanel(new java.awt.GridBagLayout());

    /** Platz für den Umschalter der rechten Leiste (Chat | Analysen | Aufträge); das Hauptfenster stellt ihn hinein. */
    public javax.swing.JComponent switchSlot() {
        return switchSlot;
    }

    ChatSidebar(ChatPanel chat) {
        super(new BorderLayout());
        this.chat = chat;
        setBackground(Comic.SURFACE);
        JPanel actions = Comic.row(chat.headerActions(), enlarge, more);
        more.addActionListener(e -> {
            javax.swing.JPopupMenu menu = com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(
                    moreMenu(), more);
            menu.show(more, more.getWidth() - menu.getPreferredSize().width, more.getHeight());
        });
        JPanel head = new com.aresstack.spaceharvester.app.ui.controls.HeadBar(new BorderLayout(8, 0), 12, 8);
        // Links der Umschalter der Leiste an Stelle des Worts „Chat:“, gleich dahinter der Name des Chats.
        switchSlot.setOpaque(false);
        head.add(switchSlot, BorderLayout.WEST);
        head.add(chat.header(), BorderLayout.CENTER);
        // Senkrecht mittig in der Kopfzeile statt oben angeklebt (Angelo 24.11.2026).
        JPanel centered = new JPanel(new java.awt.GridBagLayout());
        centered.setOpaque(false);
        centered.add(actions);
        head.add(centered, BorderLayout.EAST);
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

    /** Was „Seitenleiste einklappen“ im Menü tut; das bestimmt das Hauptfenster. */
    public void onCollapse(Runnable action) {
        collapse = java.util.Objects.requireNonNull(action, "action");
    }

    /** Das Dreieck rechts in der Kopfzeile. */
    public IconButton moreButton() {
        return more;
    }

    /**
     * Das Menü hinter dem Dreieck: „Seitenleiste einklappen“, „Alle Chats löschen“ (mit Ja/Nein-Rückfrage) und die
     * Grenzen der Parallelität.
     */
    public javax.swing.JPopupMenu moreMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(COLLAPSE, collapseIcon());
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> collapse.run());
        menu.add(item);
        javax.swing.JMenuItem deleteAll = new javax.swing.JMenuItem(DELETE_ALL,
                com.aresstack.spaceharvester.app.ui.Glyph.TRASH);
        deleteAll.setFont(Comic.regular(13f));
        deleteAll.addActionListener(e -> chat.deleteAllChats());
        for (javax.swing.JMenuItem export : chat.exportMenus()) {
            menu.add(export);
        }
        menu.addSeparator();
        menu.add(deleteAll);
        // Die Grenzen der Parallelität wie im Menü der Aufträge (Angelo 24.11.2026).
        menu.addSeparator();
        for (javax.swing.JMenu parallel : chat.parallelMenus()) {
            menu.add(parallel);
        }
        return menu;
    }

    /**
     * Der Knopf „Groß anzeigen“ links neben „Chat einklappen“: dieselbe Leiste über die ganze Arbeitsfläche, der
     * Explorer bleibt; was er tut, bestimmt das Hauptfenster.
     */
    public IconButton enlargeButton() {
        return enlarge;
    }

    /** Zeigt am Knopf, ob die Leiste gerade groß ist: Symbol und Tooltip wechseln. */
    public void large(boolean large) {
        enlarge.setIcon(enlargeIcon(large));
        enlarge.setToolTipText(large ? SHRINK : ENLARGE);
        enlarge.getAccessibleContext().setAccessibleName(large ? SHRINK : ENLARGE);
    }

    /** Groß anzeigen: zwei Pfeile in die Ecken; in der großen Ansicht zeigen sie nach innen. */
    public static Icon enlargeIcon(boolean large) {
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

    /** Das Symbol des Umschalters mit Punkt oben rechts: eine Antwort ist da, die Leiste ist zu. */
    public static Icon unreadIcon() {
        Icon chat = ChatIcons.chat();
        return new Icon() {
            @Override
            public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
                chat.paintIcon(c, g, x, y);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                int size = 7;
                int left = x + getIconWidth() - size + 2;
                g2.setColor(Comic.SURFACE);
                g2.fillOval(left - 1, y - 3, size + 2, size + 2);
                g2.setColor(Comic.BLUE);
                g2.fillOval(left, y - 2, size, size);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return chat.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return chat.getIconHeight();
            }
        };
    }

    /** Einklappen: zwei Winkel nach rechts, wohin die Leiste verschwindet. */
    public static Icon collapseIcon() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawPolyline(new int[] {3, 7, 3}, new int[] {4, 8, 12}, 3);
                g2.drawPolyline(new int[] {8, 12, 8}, new int[] {4, 8, 12}, 3);
            }
        };
    }
}

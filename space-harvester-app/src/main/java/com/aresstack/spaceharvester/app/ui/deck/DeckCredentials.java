package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * Der EINE Weg, im Command Deck die Zugangsdaten zu ändern (Angelo, 24.11.2026): Mit gemerkten Daten meldet sich
 * jeder Bereich von selbst an (#1264). Ändert sich das Passwort oder soll ein anderer KeePass-Eintrag gelten, muss das
 * Anmeldeformular trotzdem jederzeit erreichbar sein, ohne dass vorher automatisch angemeldet wird. Jeder Bereich
 * (Terminal, FTP, NDV, BetaView, E-Akte) bekommt dafür dasselbe: den Schlüssel rechts in der Kopfleiste und im
 * Rechtsklick-Menü von „Neue Verbindung“ und jedes Reiters den Eintrag „Zugangsdaten ändern …“. Beides ruft
 * {@code edit} des Bereichs: Formular zeigen, Einträge neu aus KeePass lesen, eine Sperre nach einem Fehlschlag
 * ({@link DeckLogin}) als Grund anzeigen. Gesperrt wird nichts; „Verbinden“ im Formular darf immer.
 */
public final class DeckCredentials {

    /** Tooltip und Menütext, überall gleich. */
    public static final String TIP =
            "Zugangsdaten ändern: Anmeldeformular zeigen und KeePass neu lesen, ohne automatisch anzumelden";
    public static final String MENU_TEXT = "Zugangsdaten ändern …";

    private DeckCredentials() {
    }

    /** Der Schlüssel für die Kopfleiste eines Bereichs. */
    public static IconButton button(Runnable edit) {
        IconButton button = new IconButton(Glyph.KEY, TIP).compact();
        button.getAccessibleContext().setAccessibleName(MENU_TEXT);
        button.addActionListener(e -> edit.run());
        return button;
    }

    /** Rechtsklick auf {@code target} (Reiter, „Neue Verbindung“) bietet „Zugangsdaten ändern …“ an. */
    public static void menu(JComponent target, Runnable edit) {
        target.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }

            private void popup(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    popupMenu(edit).show(event.getComponent(), event.getX(), event.getY());
                }
            }
        });
    }

    /** Das Rechtsklick-Menü (für Tests direkt). */
    static JPopupMenu popupMenu(Runnable edit) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem item = new JMenuItem(MENU_TEXT, Glyph.KEY);
        item.setToolTipText(TIP);
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> edit.run());
        menu.add(item);
        return menu;
    }

    /**
     * Zeigt im Formular, warum nicht automatisch angemeldet wird, wenn der Eintrag nach einem Fehlschlag gesperrt ist;
     * sonst bleibt das Formular, wie es ist.
     */
    public static void showLock(String entry, Consumer<String> problem) {
        String notice = DeckLogin.notice(entry);
        if (!notice.isEmpty()) {
            problem.accept(notice);
        }
    }
}

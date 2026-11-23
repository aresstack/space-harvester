package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import javax.swing.JComponent;

/**
 * Die Verbindung des Terminals für Einstellungen › Mainframe: dieselben Felder wie „Neue Verbindung“, gespeichert
 * statt verbunden. Danach verbindet „Neue Verbindung“ direkt (Angelo, 23.11.2026).
 */
public final class ConnectionSettings {

    private ConnectionSettings() {
    }

    /** Das Formular im Einstellungsmodus; lädt die KeePass-Einträge beim ersten Zeigen. */
    public static JComponent form(ServerAccounts accounts) {
        return ConnectForm.forSettings(accounts);
    }
}

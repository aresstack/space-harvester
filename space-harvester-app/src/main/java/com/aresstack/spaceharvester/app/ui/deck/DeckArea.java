package com.aresstack.spaceharvester.app.ui.deck;

import javax.swing.Icon;

/**
 * Startseite und Arbeitsbereiche der oberen Icon-Leiste des Command Deck, in genau dieser Reihenfolge (#1141/#1142,
 * Produktentscheidung 22.11.2026). JES hat kein eigenes Symbol: Jobs und Spool gehören in den FTP-Bereich.
 */
public enum DeckArea {
    /**
     * Startseite (Angelo, 23.11.2026): beim Wechsel ins Deck vorausgewählt, damit nicht sofort eine Verbindung
     * aufgebaut wird. Erst ein Klick auf einen Bereich verbindet.
     */
    HOME("Home", "Übersicht der Bereiche – verbindet nichts", true),
    TERMINAL("Terminal", "Interaktive TN3270-Sitzungen", true),
    FTP("FTP", "MVS-Datasets und Members, Transfers, JES-Jobs und Spool", true),
    NDV("NDV", "Natural Development Server: Bibliotheken, Objekte, Quelltext", true),
    BETAVIEW("BetaView", "Beleg- und Dokumentserver", true),
    FILES("Dateien", "Lokale Dateien, SharePoint und weitere Dateisysteme", true),
    /** E-Akte (Fabasoft) über WebDAV, Anmeldung über die Einmalanmeldung (Angelo, 23.11.2026). */
    EAKTE("E-Akte", "Akten der E-Akte (Fabasoft) über WebDAV", true);

    private final String title;
    private final String hint;
    private final boolean ready;

    DeckArea(String title, String hint, boolean ready) {
        this.title = title;
        this.hint = hint;
        this.ready = ready;
    }

    public String title() {
        return title;
    }

    public String hint() {
        return hint;
    }

    /** Ob der Bereich schon bedienbar ist; die übrigen folgen in eigenen Schritten. */
    public boolean ready() {
        return ready;
    }

    Icon icon() {
        return switch (this) {
            case HOME -> DeckIcons.home();
            case TERMINAL -> DeckIcons.terminal();
            case FTP -> DeckIcons.ftp();
            case NDV -> DeckIcons.ndv();
            case BETAVIEW -> DeckIcons.betaView();
            case FILES -> DeckIcons.files();
            case EAKTE -> DeckIcons.eakte();
        };
    }
}

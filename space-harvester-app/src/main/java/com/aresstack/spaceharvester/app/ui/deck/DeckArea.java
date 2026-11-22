package com.aresstack.spaceharvester.app.ui.deck;

import javax.swing.Icon;

/**
 * Die Arbeitsbereiche der oberen Icon-Leiste des Command Deck, in genau dieser Reihenfolge (#1141/#1142,
 * Produktentscheidung 22.11.2026). JES hat kein eigenes Symbol: Jobs und Spool gehören in den FTP-Bereich.
 */
public enum DeckArea {
    TERMINAL("Terminal", "Interaktive TN3270-Sitzungen", true),
    FTP("FTP", "MVS-Datasets und Members, Transfers, JES-Jobs und Spool", false),
    NDV("NDV", "Natural Development Server: Bibliotheken, Objekte, Quelltext", true),
    BETAVIEW("BetaView", "Beleg- und Dokumentserver", false),
    FILES("Dateien", "Lokale Dateien, SharePoint und weitere Dateisysteme", false);

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
            case TERMINAL -> DeckIcons.terminal();
            case FTP -> DeckIcons.ftp();
            case NDV -> DeckIcons.ndv();
            case BETAVIEW -> DeckIcons.betaView();
            case FILES -> DeckIcons.files();
        };
    }
}

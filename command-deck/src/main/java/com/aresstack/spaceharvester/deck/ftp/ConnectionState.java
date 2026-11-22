package com.aresstack.spaceharvester.deck.ftp;

/** Zustand einer FTP-Sitzung. */
public enum ConnectionState {
    CONNECTING("Verbindet …"),
    CONNECTED("Verbunden"),
    FAILED("Fehlgeschlagen"),
    DISCONNECTED("Getrennt");

    private final String label;

    ConnectionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Nach Getrennt geht nichts mehr; Fehlgeschlagen kann durch einen erneuten Versuch wieder verbinden. */
    public boolean ended() {
        return this == DISCONNECTED;
    }
}

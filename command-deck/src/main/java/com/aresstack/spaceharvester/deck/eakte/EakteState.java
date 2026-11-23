package com.aresstack.spaceharvester.deck.eakte;

/** Zustand einer E-Akte-Verbindung. */
public enum EakteState {
    /** Anmeldung läuft. */
    CONNECTING("Melde an …"),
    /** Angemeldet; Mappen und Dokumente sind lesbar. */
    CONNECTED("Angemeldet"),
    /** Getrennt bzw. geschlossen. */
    DISCONNECTED("Getrennt"),
    /** Anmeldung gescheitert oder Sitzung abgelaufen; der Grund steht in der Sitzung. */
    FAILED("Fehler");

    private final String label;

    EakteState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

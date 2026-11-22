package com.aresstack.spaceharvester.deck.betaview;

/** Zustand einer BetaView-Anmeldung. */
public enum ConnectionState {
    /** Anmeldung läuft. */
    CONNECTING("Melde an …"),
    /** Angemeldet; Suchen und Dokumente sind möglich. */
    CONNECTED("Angemeldet"),
    /** Abgemeldet bzw. geschlossen. */
    DISCONNECTED("Abgemeldet"),
    /** Anmeldung gescheitert; der Grund steht in der Sitzung. */
    FAILED("Fehler");

    private final String label;

    ConnectionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Ob die Sitzung beendet ist und nichts mehr kommt. */
    public boolean ended() {
        return this == DISCONNECTED || this == FAILED;
    }
}

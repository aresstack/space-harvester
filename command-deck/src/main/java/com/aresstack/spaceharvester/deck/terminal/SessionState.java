package com.aresstack.spaceharvester.deck.terminal;

/** Zustand einer Terminal-Sitzung. */
public enum SessionState {
    /** Verbindung wird aufgebaut. */
    CONNECTING("Verbinde …"),
    /** Verbunden; der Host hat das Bild geschickt. */
    CONNECTED("Verbunden"),
    /** Getrennt, von Hand oder vom Host. */
    DISCONNECTED("Getrennt"),
    /** Verbindung gescheitert; der Grund steht in der Sitzung. */
    FAILED("Fehler");

    private final String label;

    SessionState(String label) {
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

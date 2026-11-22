package com.aresstack.spaceharvester.deck.ndv;

/** Zustand einer NDV-Sitzung. */
public enum NdvState {
    /** Verbindung wird aufgebaut. */
    CONNECTING("Verbinde …"),
    /** Verbunden und angemeldet. */
    CONNECTED("Verbunden"),
    /** Von Hand getrennt. */
    DISCONNECTED("Getrennt"),
    /** Verbindung gescheitert oder abgerissen; der Grund steht in der Sitzung. */
    FAILED("Fehler");

    private final String label;

    NdvState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

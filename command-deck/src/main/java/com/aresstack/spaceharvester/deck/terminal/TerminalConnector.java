package com.aresstack.spaceharvester.deck.terminal;

/** Port: legt Verbindungen zu Terminal-Hosts an, ohne sie schon aufzubauen. */
public interface TerminalConnector {

    /**
     * Legt eine Verbindung für das Profil an. Aufgebaut wird sie erst mit {@link TerminalConnection#connect()}, damit
     * die Oberfläche ihre Darstellung vorher einhängen kann.
     */
    TerminalConnection create(TerminalProfile profile, TerminalConnection.Listener listener) throws Exception;
}

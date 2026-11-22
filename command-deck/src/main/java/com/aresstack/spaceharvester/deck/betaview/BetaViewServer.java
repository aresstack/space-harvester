package com.aresstack.spaceharvester.deck.betaview;

import java.io.IOException;

/**
 * Port zum BetaView-Webserver: meldet an und liefert eine angemeldete Verbindung. Der Adapter
 * ({@code ...betaview.http}) spricht das Struts-Protokoll des Servers; das Fachmodell kennt davon nichts.
 */
public interface BetaViewServer {

    /**
     * Meldet an. Blockiert, also nie auf dem EDT.
     *
     * @param profile  Adresse und Benutzer
     * @param password Kennwort; der Aufrufer überschreibt es danach
     * @throws IOException wenn der Server nicht erreichbar ist oder die Anmeldung ablehnt
     */
    BetaViewConnection login(BetaViewProfile profile, char[] password) throws IOException;
}

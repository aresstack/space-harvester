package com.aresstack.spaceharvester.deck.eakte;

import java.io.IOException;

/**
 * Port zur E-Akte: meldet über die Einmalanmeldung an und liefert eine angemeldete Verbindung. Der Adapter
 * ({@code ...eakte.http}) spricht SAML, OpenAM und WebDAV; das Fachmodell kennt davon nichts.
 */
public interface EakteServer {

    /**
     * Meldet an. Das Kennwort geht höchstens einmal an einen Anmeldedienst aus {@link EakteProfile#loginHosts()}.
     * Blockiert, also nie auf dem EDT.
     *
     * @param profile  Adressen und Benutzer
     * @param password Kennwort; der Aufrufer überschreibt es danach
     * @throws LoginRefusedException wenn der Anmeldedienst Benutzer oder Kennwort ablehnt
     * @throws IOException           wenn die E-Akte nicht erreichbar ist oder die Anmeldung anders scheitert
     */
    EakteConnection connect(EakteProfile profile, char[] password) throws IOException;
}

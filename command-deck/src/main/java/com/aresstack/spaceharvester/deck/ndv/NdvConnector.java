package com.aresstack.spaceharvester.deck.ndv;

import java.io.IOException;

/** Port: baut Verbindungen zu NDV-Servern auf. */
public interface NdvConnector {

    /**
     * Verbindet und meldet sich an; blockiert bis dahin, nie auf dem Event Dispatch Thread aufrufen. Das Passwort
     * gehört weiter dem Aufrufer, der es danach löscht.
     *
     * @throws IOException mit lesbarer Meldung, wenn der Server nicht erreichbar ist oder die Anmeldung scheitert
     */
    NdvConnection connect(NdvProfile profile, String user, char[] password) throws IOException;
}

package com.aresstack.spaceharvester.deck.eakte;

import java.io.IOException;

/**
 * Die Sitzung ist abgelaufen und lässt sich ohne Kennwort nicht erneuern. Die Verbindung sendet das Kennwort nie von
 * sich aus erneut; der Bereich entscheidet, ob er neu anmeldet (dabei gilt die Anmeldesperre je KeePass-Eintrag).
 */
public final class SessionExpiredException extends IOException {

    public SessionExpiredException(String message) {
        super(message);
    }
}

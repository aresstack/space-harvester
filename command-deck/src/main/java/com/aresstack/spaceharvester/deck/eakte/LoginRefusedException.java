package com.aresstack.spaceharvester.deck.eakte;

import java.io.IOException;

/**
 * Der Anmeldedienst hat Benutzer oder Kennwort abgelehnt. Nie wiederholen: Jeder weitere Versuch mit denselben
 * Zugangsdaten bringt das Konto der Sperre näher.
 */
public final class LoginRefusedException extends IOException {

    public LoginRefusedException(String message) {
        super(message);
    }
}

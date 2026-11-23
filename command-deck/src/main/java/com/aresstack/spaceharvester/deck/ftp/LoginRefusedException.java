package com.aresstack.spaceharvester.deck.ftp;

/**
 * Der Host hat die Anmeldung abgelehnt (etwa 530: Passwort falsch oder abgelaufen). Nie wiederholen: Jeder weitere
 * Versuch mit denselben Zugangsdaten bringt das Konto der Sperre näher.
 */
public final class LoginRefusedException extends FtpReplyException {

    public LoginRefusedException(String message, int code) {
        super(message, code);
    }
}

package com.aresstack.spaceharvester.deck.access;

import java.util.List;

/**
 * Port: die Host-Zugänge des Command Deck. Die App liefert sie aus KeePass, über dieselbe Kopplung wie die
 * Wissensquellen; es gibt keinen zweiten Passwortspeicher. Beide Aufrufe können blockieren (nie auf dem EDT).
 */
public interface ServerAccounts {

    /** Alle Einträge ohne Passwörter. */
    List<ServerAccount> accounts() throws Exception;

    /** Liest Benutzer und Passwort eines Eintrags; der Aufrufer schließt das Ergebnis. */
    ServerCredentials credentials(String entry) throws Exception;

    /** Kein Passwortspeicher angebunden (Tests, oder KeePass nicht gekoppelt). */
    ServerAccounts NONE = new ServerAccounts() {
        @Override
        public List<ServerAccount> accounts() {
            return List.of();
        }

        @Override
        public ServerCredentials credentials(String entry) throws Exception {
            throw new IllegalStateException("Kein Passwortspeicher angebunden");
        }
    };
}

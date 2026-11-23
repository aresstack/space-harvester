package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.access.LoginGuard;

/**
 * Der EINE {@link LoginGuard} der App für alle Bereiche des Command Deck (Terminal, FTP, NDV, BetaView, SharePoint):
 * Ein fehlgeschlagener Login sperrt den KeePass-Eintrag für jeden automatischen Versuch, überall, bis eine Anmeldung
 * klappt (Angelo, 23.11.2026, Schutz vor der Kontosperre). Die Sperren stehen im Zustandsspeicher der App und
 * überstehen den Neustart.
 */
public final class DeckLogin {

    private static final LoginGuard GUARD = new LoginGuard(new LoginGuard.Store() {
        @Override
        public String get(String key) {
            return AppState.shared().get(key);
        }

        @Override
        public void put(String key, String value) {
            AppState.shared().put(key, value);
        }
    });

    private DeckLogin() {
    }

    /** Der gemeinsame Wächter; jeder Bereich fragt ihn vor einem automatischen Anmeldeversuch. */
    public static LoginGuard guard() {
        return GUARD;
    }

    /**
     * Der Text für das Formular, wenn der Eintrag gesperrt ist, etwa „Letzte Anmeldung mit „X“ fehlgeschlagen:
     * Passwort abgelaufen. Bitte Zugangsdaten prüfen.“; leer, wenn der Eintrag frei ist.
     */
    public static String notice(String entry) {
        return GUARD.failure(entry).map(failure -> "Letzte Anmeldung mit „" + failure.entry() + "“ fehlgeschlagen: "
                + failure.reason() + ". Bitte Zugangsdaten prüfen.").orElse("");
    }
}

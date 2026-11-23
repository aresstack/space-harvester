package com.aresstack.spaceharvester.deck.terminal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Hat der Host die Anmeldung abgelehnt? Ein 3270-Host meldet das nicht im Protokoll, nur als Text auf dem Bild. Für den
 * Schutz vor der Kontosperre gilt: im Zweifel abgelehnt. Ein falscher Fehlschlag kostet nur einen Dialog, ein falscher
 * Erfolg löst die Sperre und kann das Konto kosten.
 */
public final class LoginCheck {

    /** Wörter, an denen eine Ablehnung zu erkennen ist (deutsch und englisch), klein geschrieben. */
    static final List<String> REJECTION_WORDS = List.of(
            "ungültig", "ungueltig", "falsch", "abgelaufen", "gesperrt", "nicht berechtigt", "keine berechtigung",
            "verweigert", "fehlgeschlagen", "unbekannt",
            "invalid", "incorrect", "expired", "revoked", "not authorized", "not authorised", "denied", "failed",
            "suspended", "locked out", "unknown user", "not recognized");

    private LoginCheck() {
    }

    /**
     * Prüft das Bild nach der Anmeldung.
     *
     * @param screen    das ganze Bild als Text, Zeile für Zeile
     * @param loginMask ob wieder eine Anmeldemaske steht (Eingabefeld, danach ein verdecktes Feld)
     * @return leer, wenn die Anmeldung geklappt hat; sonst der Grund, möglichst die Zeile, die der Host zeigt
     */
    public static Optional<String> rejected(String screen, boolean loginMask) {
        String text = screen == null ? "" : screen;
        for (String line : text.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (String word : REJECTION_WORDS) {
                if (lower.contains(word)) {
                    return Optional.of(line.trim().replaceAll("\\s{2,}", " "));
                }
            }
        }
        if (loginMask) {
            return Optional.of("die Anmeldemaske erscheint wieder");
        }
        return Optional.empty();
    }
}

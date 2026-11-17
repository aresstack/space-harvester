package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import java.awt.Color;

/**
 * Woher Passwort bzw. Token einer Quelle kommen, in EINER Formulierung für Quellenverwaltung, Quellenseite und
 * Sicherheit. Eine Quelle mit KeePass-Eintrag heißt nie „fehlt“ (Angelo, #560): Ihr Platzhalter bleibt absichtlich
 * leer, der Wert kommt beim Start aus KeePass in die Sitzung.
 *
 * @param text    was neben „Passwort“ bzw. „Token“ steht
 * @param missing ob die Quelle ohne Eingabe nicht starten kann (rot)
 */
public record SecretState(String text, boolean missing) {

    /**
     * @param settings die geladene Konfiguration (für den KeePass-Eintrag)
     * @param session  ob die Sitzung schon einen Wert hält (aus KeePass, Tresor oder eingetippt)
     * @param stored   ob der Tresor einen Wert für genau diese Anmeldung hält
     */
    public static SecretState of(AppSettings settings, AppSettings.SourceEntry entry, boolean session,
                                 boolean stored) {
        String keePassEntry = settings == null ? null : settings.keePassEntry(entry.id());
        if (keePassEntry != null && entry.secretMissing()) {
            return new SecretState(session ? "aus KeePass („" + keePassEntry + "“)"
                    : "aus KeePass („" + keePassEntry + "“), noch nicht gelesen", false);
        }
        if (stored) {
            return new SecretState("verschlüsselt gespeichert", false);
        }
        if (session) {
            return new SecretState("für diese Sitzung eingegeben", false);
        }
        if (!entry.secretMissing()) {
            return new SecretState("gesetzt (Property/Umgebung)", false);
        }
        return new SecretState("fehlt", true);
    }

    /** Rot nur, wenn wirklich etwas fehlt. */
    public Color color() {
        return missing ? Comic.RED : Comic.INK;
    }
}

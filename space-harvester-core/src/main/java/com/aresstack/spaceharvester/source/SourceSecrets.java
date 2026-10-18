package com.aresstack.spaceharvester.source;

import java.util.Optional;

/**
 * Zugangsdaten des einbettenden Programms für Quellen (#188), etwa aus KeePassRPC, einem Tresor oder einer Abfrage
 * beim Benutzer. Der Core fragt erst beim ersten Zugriff einer Quelle und nur, was ihre Konfiguration nicht nennt;
 * er speichert und protokolliert nichts davon. Umsetzungen dürfen blockieren und interaktiv sein.
 */
@FunctionalInterface
public interface SourceSecrets {

    /**
     * @param ref {@code source:<id>} für die Anmeldung (Benutzer und Passwort, ein Token ohne Benutzer) oder
     *            {@code source:<id>:client-certificate} für das Passwort der PKCS#12-Datei
     * @return die Zugangsdaten, leer ohne Eintrag; der Aufrufer löscht {@link Credential#secret()} nach Gebrauch
     * @throws Unavailable wenn der Anbieter nicht bereit ist, den Zugriff verweigert oder abgebrochen wurde
     */
    Optional<Credential> resolve(String ref);

    /**
     * @param principal Benutzername oder {@code null} für ein Token ohne Benutzer
     * @param secret    Passwort oder Token; das {@code toString} zeigt es nie
     */
    record Credential(String principal, char[] secret) {

        public Credential {
            if (secret == null) {
                throw new IllegalArgumentException("secret fehlt");
            }
        }

        @Override
        public String toString() {
            return "Credential[principal=" + principal + ", secret=***]";
        }
    }

    /** Der Anbieter kann gerade nicht liefern; die Meldung nennt kein Geheimnis. */
    final class Unavailable extends RuntimeException {

        public Unavailable(String message) {
            super(message);
        }
    }
}

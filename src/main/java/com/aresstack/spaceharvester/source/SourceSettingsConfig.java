package com.aresstack.spaceharvester.source;

import java.net.URI;
import java.util.Map;

/**
 * Verbindungsparameter einer Quelle, wie sie aus der Konfiguration kommen (ohne Spring-Bezug).
 *
 * @param id      Quellen-ID, z. B. {@code confluence}; getrennt von fachlichen Schlüsseln wie Space-Keys
 * @param type    Quellentyp, z. B. {@code confluence}
 * @param title   Anzeigename oder {@code null}
 * @param baseUrl Basis-URL der Quelle
 * @param auth    Anmeldung; {@code null} heißt anonym
 * @param options weitere Einstellungen des Adapters (z. B. {@code pageSize}); nie Zugangsdaten
 */
public record SourceSettingsConfig(String id, String type, String title, URI baseUrl, Auth auth,
                                   Map<String, String> options) {

    public SourceSettingsConfig {
        auth = auth == null ? Auth.anonymous() : auth;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** Anmeldeart einer Quelle. */
    public enum AuthType {
        /** Ohne Anmeldung. */
        NONE,
        /** HTTP Basic mit Benutzername und Passwort. */
        BASIC,
        /** Personal Access Token als {@code Authorization: Bearer}. */
        PAT
    }

    /**
     * Anmeldedaten einer Quelle. Das {@code toString} zeigt keine Geheimnisse.
     *
     * @param type     Anmeldeart; {@code null} gilt als nicht angegeben
     * @param username Benutzername (nur {@link AuthType#BASIC})
     * @param password Passwort (nur {@link AuthType#BASIC})
     * @param token    Personal Access Token (nur {@link AuthType#PAT})
     */
    public record Auth(AuthType type, String username, String password, String token) {

        public static Auth anonymous() {
            return new Auth(AuthType.NONE, null, null, null);
        }

        @Override
        public String toString() {
            return "Auth[type=" + type + ", username=" + (username == null ? "-" : username) + ", password="
                    + masked(password) + ", token=" + masked(token) + "]";
        }

        private static String masked(String secret) {
            return secret == null || secret.isEmpty() ? "-" : "***";
        }
    }
}

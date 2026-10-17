package com.aresstack.spaceharvester.source;

import java.net.URI;
import java.nio.file.Path;
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
 * @param clientCertificate TLS-Client-Zertifikat (mTLS); {@code null} heißt ohne
 * @param trustBundle Name eines Spring-SSL-Bundles mit den vertrauenswürdigen Server-/Proxy-CAs oder {@code null}
 *                    für das Vertrauen der JVM
 */
public record SourceSettingsConfig(String id, String type, String title, URI baseUrl, Auth auth,
                                   Map<String, String> options, ClientCertificate clientCertificate,
                                   String trustBundle) {

    public SourceSettingsConfig {
        auth = auth == null ? Auth.anonymous() : auth;
        options = options == null ? Map.of() : Map.copyOf(options);
        clientCertificate = clientCertificate == null ? ClientCertificate.disabled() : clientCertificate;
        trustBundle = trustBundle == null || trustBundle.isBlank() ? null : trustBundle.trim();
    }

    /** Quelle ohne Client-Zertifikat, mit dem Vertrauen der JVM. */
    public SourceSettingsConfig(String id, String type, String title, URI baseUrl, Auth auth,
                                Map<String, String> options) {
        this(id, type, title, baseUrl, auth, options, null, null);
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

    /** Woher das Client-Zertifikat kommt. */
    public enum CertificateStoreType {
        /** PKCS#12-Datei mit Zertifikat und privatem Schlüssel (Windows, Linux, Container). */
        PKCS12,
        /** Windows-Zertifikatsspeicher {@code CurrentUser\My} des ausführenden Kontos (nur unter Windows). */
        WINDOWS_MY
    }

    /**
     * TLS-Client-Zertifikat einer Quelle (mTLS), getrennt von der Anmeldung ({@link Auth}). Das {@code toString}
     * zeigt kein Passwort.
     *
     * @param enabled    {@code true} schaltet mTLS für die Quelle ein; ohne mTLS gilt Standard-TLS
     * @param type       Zertifikatsquelle
     * @param path       PKCS#12-Datei (nur {@link CertificateStoreType#PKCS12})
     * @param alias      Alias des Zertifikats im Speicher; wird erzwungen
     * @param password   Passwort der PKCS#12-Datei und ihres Schlüssels (nur {@link CertificateStoreType#PKCS12})
     * @param thumbprint optionaler SHA-1-Fingerabdruck (wie in Windows angezeigt) oder SHA-256 zur Kontrolle
     */
    public record ClientCertificate(boolean enabled, CertificateStoreType type, Path path, String alias,
                                    String password, String thumbprint) {

        public static ClientCertificate disabled() {
            return new ClientCertificate(false, null, null, null, null, null);
        }

        @Override
        public String toString() {
            return "ClientCertificate[enabled=" + enabled + ", type=" + type + ", path=" + (path == null ? "-" : path)
                    + ", alias=" + (alias == null ? "-" : alias) + ", password="
                    + (password == null || password.isEmpty() ? "-" : "***") + ", thumbprint="
                    + (thumbprint == null ? "-" : thumbprint) + "]";
        }
    }
}

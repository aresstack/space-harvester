package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester}: die benannten Quellen. Zugangsdaten gehören nicht in Dateien im
 * Repository, sondern kommen über Platzhalter aus Umgebungsvariablen (siehe README).
 */
@ConfigurationProperties("space-harvester")
public record SpaceHarvesterProperties(List<Source> sources) {

    public SpaceHarvesterProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * Eine Quelle.
     *
     * @param id      Quellen-ID für die API, z. B. {@code confluence}
     * @param type    Quellentyp, derzeit {@code confluence}
     * @param title   Anzeigename (optional)
     * @param baseUrl Basis-URL, bei Confluence ohne {@code /rest}
     * @param auth    Anmeldung (optional; ohne Angabe anonym)
     * @param options weitere Adapter-Einstellungen, z. B. {@code pageSize}
     * @param clientCertificate TLS-Client-Zertifikat (mTLS, optional), unabhängig von {@code auth}
     * @param trustBundle Name eines Spring-SSL-Bundles ({@code spring.ssl.bundle.*}) mit den vertrauenswürdigen
     *                    Server-/Proxy-CAs (optional; ohne Angabe das Vertrauen der JVM)
     */
    public record Source(String id, String type, String title, URI baseUrl, Auth auth, Map<String, String> options,
                         ClientCertificate clientCertificate, String trustBundle) {

        SourceSettingsConfig toSettings() {
            return new SourceSettingsConfig(id, type, title, baseUrl, auth == null ? null : auth.toSettings(),
                    options, clientCertificate == null ? null : clientCertificate.toSettings(), trustBundle);
        }
    }

    /**
     * TLS-Client-Zertifikat einer Quelle; {@code toString} zeigt kein Passwort.
     *
     * @param enabled    {@code true} schaltet mTLS für die Quelle ein
     * @param type       {@code pkcs12} (Datei) oder {@code windows-my} (Windows-Zertifikatsspeicher des Dienstkontos)
     * @param path       PKCS#12-Datei, nur bei {@code pkcs12}
     * @param alias      Alias des Zertifikats
     * @param password   Passwort der PKCS#12-Datei, nur bei {@code pkcs12}
     * @param thumbprint optionaler SHA-1- oder SHA-256-Fingerabdruck zur Kontrolle
     */
    public record ClientCertificate(boolean enabled, SourceSettingsConfig.CertificateStoreType type, Path path,
                                    String alias, String password, String thumbprint) {

        SourceSettingsConfig.ClientCertificate toSettings() {
            return new SourceSettingsConfig.ClientCertificate(enabled, type, path, alias, password, thumbprint);
        }

        @Override
        public String toString() {
            return toSettings().toString();
        }
    }

    /**
     * Anmeldung einer Quelle; {@code toString} zeigt keine Geheimnisse.
     *
     * @param type     {@code none}, {@code basic} (Benutzername und Passwort) oder {@code pat} (Personal Access Token)
     * @param username nur bei {@code basic}
     * @param password nur bei {@code basic}
     * @param token    nur bei {@code pat}
     */
    public record Auth(SourceSettingsConfig.AuthType type, String username, String password, String token) {

        SourceSettingsConfig.Auth toSettings() {
            return new SourceSettingsConfig.Auth(type, username, password, token);
        }

        @Override
        public String toString() {
            return toSettings().toString();
        }
    }
}

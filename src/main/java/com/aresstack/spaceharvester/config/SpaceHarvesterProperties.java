package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.net.URI;
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
     */
    public record Source(String id, String type, String title, URI baseUrl, Auth auth, Map<String, String> options) {

        SourceSettingsConfig toSettings() {
            return new SourceSettingsConfig(id, type, title, baseUrl, auth == null ? null : auth.toSettings(),
                    options);
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

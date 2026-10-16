package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration unter {@code space-harvester}: die benannten Quellen und das ausgehende Netzwerk. Zugangsdaten gehören nicht in Dateien im
 * Repository, sondern kommen über Platzhalter aus Umgebungsvariablen (siehe README).
 */
@ConfigurationProperties("space-harvester")
public record SpaceHarvesterProperties(List<Source> sources, Network network) {

    public SpaceHarvesterProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        network = network == null ? new Network(null) : network;
    }

    /** Ausgehende Verbindungen aller Quellen. */
    public record Network(Proxy proxy) {

        public Network {
            proxy = proxy == null ? new Proxy(null, null, null, null, null, null, null, null) : proxy;
        }
    }

    /**
     * Proxy-Route je Ziel-URL (#34); ohne Angabe gehen alle Verbindungen direkt.
     *
     * @param mode           {@code disabled} (Standard), {@code manual-proxy}, {@code pac-url-manual},
     *                       {@code pac-url-powershell}, {@code pac-url-windows-settings}, {@code pac-url-wscript} oder
     *                       {@code windows-static-proxy}
     * @param pacUrl         nur bei {@code pac-url-manual}
     * @param proxyHost      nur bei {@code manual-proxy}
     * @param proxyPort      nur bei {@code manual-proxy}
     * @param nonProxyHosts  immer direkt: exakte Hostnamen oder {@code *.suffix}
     * @param resolveTimeout Frist je Auflösung (Standard 20 s)
     * @param cacheTtl       Gültigkeit einer gefundenen Route (Standard 10 min)
     * @param failureTtl     Gültigkeit einer gescheiterten Auflösung (Standard 1 min)
     */
    public record Proxy(ProxySettings.Mode mode, URI pacUrl, String proxyHost, Integer proxyPort,
                        List<String> nonProxyHosts, Duration resolveTimeout, Duration cacheTtl, Duration failureTtl) {

        ProxySettings toSettings() {
            return new ProxySettings(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, resolveTimeout, cacheTtl,
                    failureTtl);
        }
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

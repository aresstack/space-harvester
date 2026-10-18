package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.net.URI;
import java.nio.file.Path;
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
        network = network == null ? new Network(null, null, null) : network;
    }

    /**
     * Ausgehende Verbindungen aller Quellen.
     *
     * @param proxy          Proxy-Route je Ziel-URL
     * @param asyncTransport {@code web-client} (Standard): native Seiten-Snapshots nicht blockierend über Spring
     *                       WebClient (#47), mit derselben Route, demselben Vertrauen und demselben Client-Zertifikat;
     *                       {@code disabled}: Rückfall auf den synchronen Transport
     * @param tls            Vertrauen für Quellen und KI-Dienst ohne eigenes {@code trust-bundle} (#189)
     */
    public record Network(Proxy proxy, ConfiguredSourceCatalog.AsyncTransport asyncTransport, Tls tls) {

        public Network {
            tls = tls == null ? new Tls(null, null, null, null) : tls;
            proxy = proxy == null ? new Proxy(null, null, null, null, null, null, null, null, null, null) : proxy;
            asyncTransport = asyncTransport == null ? ConfiguredSourceCatalog.AsyncTransport.WEB_CLIENT : asyncTransport;
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
     * @param auth           Anmeldung am Proxy mit BASIC (optional, #189)
     * @param pacDiscoveryScript eigenes Skript, das die PAC-Adresse ausgibt: PowerShell bei {@code pac-url-powershell},
     *                       VBScript bei {@code pac-url-wscript} (optional, #189)
     */
    public record Proxy(ProxySettings.Mode mode, URI pacUrl, String proxyHost, Integer proxyPort,
                        List<String> nonProxyHosts, Duration resolveTimeout, Duration cacheTtl, Duration failureTtl,
                        ProxyAuth auth, String pacDiscoveryScript) {

        ProxySettings toSettings() {
            return new ProxySettings(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, resolveTimeout, cacheTtl,
                    failureTtl, auth == null ? null : auth.toAuthentication(), pacDiscoveryScript);
        }
    }

    /**
     * Vertrauensquellen wie {@code network.tls} im Enterprise AI Client; ein Zertifikat gilt, sobald eine Quelle es
     * akzeptiert. Ohne Angabe nur der JVM-Truststore.
     *
     * @param useJvmDefault      JVM-Truststore (Standard {@code true})
     * @param useWindowsRoot     Windows-Stammzertifikate (Standard {@code false}; nur unter Windows)
     * @param useWindowsCaStores Windows-Speicher Root und CA samt Zwischenzertifikaten (Standard {@code false})
     * @param caCertificatesFile PEM- oder DER-Datei mit weiteren CA-Zertifikaten (optional)
     */
    public record Tls(Boolean useJvmDefault, Boolean useWindowsRoot, Boolean useWindowsCaStores,
                      Path caCertificatesFile) {

        TrustSettings toSettings() {
            return new TrustSettings(useJvmDefault == null || useJvmDefault, Boolean.TRUE.equals(useWindowsRoot),
                    Boolean.TRUE.equals(useWindowsCaStores), caCertificatesFile);
        }
    }

    /**
     * Anmeldung am Proxy mit BASIC, auch im HTTPS-Tunnel. Das Passwort kommt über einen Platzhalter aus einer
     * Umgebungsvariable oder einem Secret-Speicher, nie aus einer Datei im Repository.
     *
     * @param username Benutzername
     * @param password Passwort
     */
    public record ProxyAuth(String username, String password) {

        ProxySettings.Authentication toAuthentication() {
            return new ProxySettings.Authentication(username, password == null ? null : password.toCharArray());
        }

        @Override
        public String toString() {
            return "ProxyAuth[username=" + username + ", password=" + (password == null ? "-" : "***") + "]";
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
     * @param clientCertificate TLS-Client-Zertifikat (mTLS, optional), unabhängig von {@code auth}
     * @param trustBundle Name eines Spring-SSL-Bundles ({@code spring.ssl.bundle.*}) mit den vertrauenswürdigen
     *                    Server-/Proxy-CAs (optional; ohne Angabe {@code network.tls})
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

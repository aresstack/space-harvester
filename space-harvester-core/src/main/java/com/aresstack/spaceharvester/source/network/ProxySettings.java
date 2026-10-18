package com.aresstack.spaceharvester.source.network;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Proxy-Einstellungen des Dienstes (ohne Spring-Bezug), gültig für alle Quellen.
 *
 * @param mode           Art der Routenentscheidung; {@code null} gilt als {@link Mode#DISABLED}
 * @param pacUrl         PAC-Adresse für {@link Mode#PAC_URL_MANUAL}
 * @param proxyHost      Proxy-Host für {@link Mode#MANUAL_PROXY}
 * @param proxyPort      Proxy-Port für {@link Mode#MANUAL_PROXY}
 * @param nonProxyHosts  Hosts, die immer direkt gehen: exakter Name oder {@code *.suffix}
 * @param resolveTimeout Frist für eine Auflösung (PowerShell, PAC-Download und -Auswertung zusammen)
 * @param cacheTtl       wie lange eine gefundene Route gilt
 * @param failureTtl     wie lange eine gescheiterte Auflösung gilt, bevor erneut aufgelöst wird
 * @param authentication Anmeldung am Proxy (#189); {@code null} ohne
 * @param pacDiscoveryScript eigenes Skript, das die PAC-Adresse ausgibt (#189), wie
 *                       {@code network.proxy.pacDiscoveryScript} im Enterprise AI Client: PowerShell bei
 *                       {@link Mode#PAC_URL_POWERSHELL}, VBScript bei {@link Mode#PAC_URL_WSCRIPT}; {@code null}
 *                       (oder leer) für das eingebaute Skript
 */
public record ProxySettings(Mode mode, URI pacUrl, String proxyHost, Integer proxyPort, List<String> nonProxyHosts,
                            Duration resolveTimeout, Duration cacheTtl, Duration failureTtl,
                            Authentication authentication, String pacDiscoveryScript) {

    /** Verweis, unter dem der Core das Proxy-Passwort beim Anbieter des Programms erfragt ({@code SourceSecrets}). */
    public static final String SECRET_REF = "network:proxy";

    public static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    public static final Duration DEFAULT_FAILURE_TTL = Duration.ofMinutes(1);

    public ProxySettings {
        mode = mode == null ? Mode.DISABLED : mode;
        nonProxyHosts = nonProxyHosts == null ? List.of() : List.copyOf(nonProxyHosts);
        resolveTimeout = resolveTimeout == null ? DEFAULT_RESOLVE_TIMEOUT : resolveTimeout;
        cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;
        failureTtl = failureTtl == null ? DEFAULT_FAILURE_TTL : failureTtl;
        pacDiscoveryScript = pacDiscoveryScript == null || pacDiscoveryScript.isBlank() ? null : pacDiscoveryScript;
    }

    /** Ohne Anmeldung am Proxy. */
    public ProxySettings(Mode mode, URI pacUrl, String proxyHost, Integer proxyPort, List<String> nonProxyHosts,
                         Duration resolveTimeout, Duration cacheTtl, Duration failureTtl) {
        this(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, resolveTimeout, cacheTtl, failureTtl, null);
    }

    /** Mit dem eingebauten Skript zur PAC-Ermittlung. */
    public ProxySettings(Mode mode, URI pacUrl, String proxyHost, Integer proxyPort, List<String> nonProxyHosts,
                         Duration resolveTimeout, Duration cacheTtl, Duration failureTtl,
                         Authentication authentication) {
        this(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, resolveTimeout, cacheTtl, failureTtl, authentication,
                null);
    }

    /** Dieselben Einstellungen mit dieser Anmeldung am Proxy ({@code null} ohne). */
    public ProxySettings withAuthentication(Authentication value) {
        return new ProxySettings(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, resolveTimeout, cacheTtl,
                failureTtl, value, pacDiscoveryScript);
    }

    /** Dieselben Einstellungen mit diesem Skript zur PAC-Ermittlung ({@code null} für das eingebaute). */
    public ProxySettings withPacDiscoveryScript(String script) {
        return new ProxySettings(mode, pacUrl, proxyHost, proxyPort, nonProxyHosts, resolveTimeout, cacheTtl,
                failureTtl, authentication, script);
    }

    /** Ohne Proxy: jede Verbindung geht direkt (Standard ohne Konfiguration). */
    public static ProxySettings disabled() {
        return new ProxySettings(Mode.DISABLED, null, null, null, null, null, null, null);
    }

    /**
     * Anmeldung am Proxy mit BASIC (wie {@code ProxyAuthMode.BASIC} im Enterprise AI Client), auch im
     * HTTPS-Tunnel ({@code CONNECT}). Fehlt das Passwort, fragt der Core beim ersten Proxy-Zugriff den Anbieter des
     * Programms unter {@value #SECRET_REF}; dessen Benutzername gilt, wenn hier keiner steht.
     *
     * @param username Benutzername oder {@code null}, wenn der Anbieter ihn liefert
     * @param password Passwort oder {@code null}; das {@code toString} zeigt es nie
     */
    public record Authentication(String username, char[] password) {

        public Authentication {
            username = username == null || username.isBlank() ? null : username.strip();
            password = password == null || password.length == 0 ? null : password.clone();
        }

        /** Nur der Benutzername, das Passwort kommt vom Anbieter. */
        public static Authentication basic(String username) {
            return new Authentication(username, null);
        }

        @Override
        public char[] password() {
            return password == null ? null : password.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Authentication that && java.util.Objects.equals(username, that.username)
                    && java.util.Arrays.equals(password, that.password);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(username, java.util.Arrays.hashCode(password));
        }

        @Override
        public String toString() {
            return "Authentication[basic, username=" + username + ", password=" + (password == null ? "-" : "***")
                    + "]";
        }
    }

    /**
     * Die wählbaren Modi, je einer aus {@code win-proxy-java}. Die dort noch nicht umgesetzten nativen Modi
     * ({@code WINDOWS_NATIVE_*}) und der alte PowerShell-Routenauflöser fehlen bewusst und lassen sich nicht
     * konfigurieren.
     */
    public enum Mode {
        /** Kein Proxy, jede Verbindung direkt. */
        DISABLED,
        /** Fester Proxy aus {@code proxy-host}/{@code proxy-port}. */
        MANUAL_PROXY,
        /** PAC-Skript von einer festen Adresse ({@code pac-url}); auch ohne Windows, z. B. im Container. */
        PAC_URL_MANUAL,
        /** PAC-Adresse aus {@code AutoConfigURL} (HKCU des Dienstkontos) per Inline-PowerShell; nur Windows. */
        PAC_URL_POWERSHELL,
        /** PAC-Adresse aus den Windows-Interneteinstellungen per {@code reg.exe}; nur Windows. */
        PAC_URL_WINDOWS_SETTINGS,
        /** PAC-Adresse per VBScript ({@code cscript}); nur Windows. */
        PAC_URL_WSCRIPT,
        /** Fester Proxy aus den Windows-Interneteinstellungen ({@code ProxyServer}/{@code ProxyOverride}); nur Windows. */
        WINDOWS_STATIC_PROXY
    }
}

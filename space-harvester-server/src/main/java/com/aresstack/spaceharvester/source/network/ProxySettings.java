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
 */
public record ProxySettings(Mode mode, URI pacUrl, String proxyHost, Integer proxyPort, List<String> nonProxyHosts,
                            Duration resolveTimeout, Duration cacheTtl, Duration failureTtl) {

    public static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    public static final Duration DEFAULT_FAILURE_TTL = Duration.ofMinutes(1);

    public ProxySettings {
        mode = mode == null ? Mode.DISABLED : mode;
        nonProxyHosts = nonProxyHosts == null ? List.of() : List.copyOf(nonProxyHosts);
        resolveTimeout = resolveTimeout == null ? DEFAULT_RESOLVE_TIMEOUT : resolveTimeout;
        cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;
        failureTtl = failureTtl == null ? DEFAULT_FAILURE_TTL : failureTtl;
    }

    /** Ohne Proxy: jede Verbindung geht direkt (Standard ohne Konfiguration). */
    public static ProxySettings disabled() {
        return new ProxySettings(Mode.DISABLED, null, null, null, null, null, null, null);
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

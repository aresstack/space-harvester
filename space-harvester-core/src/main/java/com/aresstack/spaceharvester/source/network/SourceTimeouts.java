package com.aresstack.spaceharvester.source.network;

import java.time.Duration;

/**
 * Netz-Fristen der Quellen unter {@code space-harvester.network.timeouts} (Angelo 26.11.2026: keine fest eingebauten
 * Werte). Sie gelten für alle Quellen, wie die Frist der Proxy-Auflösung; leer heißt jeweils die Vorgabe.
 *
 * @param delegatedCheck Prüfung mit dem Confluence-Token eines Nutzers (delegierte Anmeldung)
 *                       ({@code delegated-check}); Vorgabe {@link #DEFAULT_DELEGATED_CHECK}
 * @param webDavRequest  eine WebDAV-Anfrage an BSCW ({@code webdav-request}); Vorgabe {@link #DEFAULT_WEBDAV_REQUEST}
 * @param probe          jede Netzstufe der Verbindungsprüfung einer Quelle ({@code probe}); Vorgabe
 *                       {@link #DEFAULT_PROBE}
 */
public record SourceTimeouts(Duration delegatedCheck, Duration webDavRequest, Duration probe) {

    public static final Duration DEFAULT_DELEGATED_CHECK = Duration.ofSeconds(10);
    public static final Duration DEFAULT_WEBDAV_REQUEST = Duration.ofSeconds(90);
    public static final Duration DEFAULT_PROBE = Duration.ofSeconds(15);
    /** Nichts eingestellt: überall die Vorgabe. */
    public static final SourceTimeouts DEFAULTS = new SourceTimeouts(null, null, null);

    public SourceTimeouts {
        delegatedCheck = positive("delegated-check", delegatedCheck, DEFAULT_DELEGATED_CHECK);
        webDavRequest = positive("webdav-request", webDavRequest, DEFAULT_WEBDAV_REQUEST);
        probe = positive("probe", probe, DEFAULT_PROBE);
    }

    private static Duration positive(String key, Duration value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("space-harvester.network.timeouts." + key + " muss positiv sein");
        }
        return value;
    }
}

package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;

/**
 * Proxy und TLS-Vertrauen für alle Quellen und den KI-Dienst, unter {@code space-harvester.network.*}. Nach dem
 * Aktivieren bekommen neue Verbindungen neue Transportinstanzen; laufende Anfragen enden über die alten.
 *
 * @param proxy Proxy-Einstellungen; {@code null} gilt als ohne Proxy
 * @param trust Vertrauen; {@code null} gilt als nur JVM-Truststore
 */
public record NetworkSettings(ProxySettings proxy, TrustSettings trust) {

    public static final String PROXY = "network.proxy";
    public static final String TLS = "network.tls";

    public static final NetworkSettings DEFAULT = new NetworkSettings(ProxySettings.disabled(), TrustSettings.jvm());

    public NetworkSettings {
        proxy = proxy == null ? ProxySettings.disabled() : proxy;
        trust = trust == null ? TrustSettings.jvm() : trust;
    }
}

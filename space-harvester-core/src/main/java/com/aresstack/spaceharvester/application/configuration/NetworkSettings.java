package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy und TLS-Vertrauen für alle Quellen und den KI-Dienst, unter {@code space-harvester.network.*}. Nach dem
 * Aktivieren nehmen neue Verbindungen die neue Route und das neue Vertrauen; laufende Anfragen enden über die alten.
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

    /**
     * Probleme unter {@link #PROXY} und {@link #TLS}, leer wenn sich die Einstellungen zur Laufzeit einsetzen lassen;
     * ohne etwas aufzulösen. Die Meldungen nennen weder Passwort noch Inhalt der CA-Datei.
     */
    public List<ConfigurationProblem> problems() {
        List<ConfigurationProblem> problems = new ArrayList<>();
        ProxyRoutes.problems(proxy).forEach(problem -> problems.add(new ConfigurationProblem(PROXY, problem)));
        TrustPolicy.problems(trust).forEach(problem -> problems.add(new ConfigurationProblem(TLS, problem)));
        return problems;
    }
}

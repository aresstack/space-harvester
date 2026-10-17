package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.network.ProxySettings;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Die Proxy-Einstellungen, wie der Assistent sie bearbeitet: Modus, PAC-Adresse, fester Proxy und Ausnahmen. Sie
 * gelten für alle Quellen ({@code space-harvester.network.proxy}). Fristen, die der Assistent nicht zeigt, kommen aus
 * der bisherigen Datei und bleiben erhalten. Die Auflösung selbst macht der Core (win-proxy-java).
 */
public record ProxyDraft(ProxySettings.Mode mode, String pacUrl, String proxyHost, String proxyPort,
                         String nonProxyHosts, Duration resolveTimeout, Duration cacheTtl, Duration failureTtl) {

    public ProxyDraft {
        mode = mode == null ? ProxySettings.Mode.DISABLED : mode;
        pacUrl = trim(pacUrl);
        proxyHost = trim(proxyHost);
        proxyPort = trim(proxyPort);
        nonProxyHosts = trim(nonProxyHosts);
    }

    /** Der Entwurf zu bestehenden Einstellungen. */
    public static ProxyDraft of(ProxySettings settings) {
        return new ProxyDraft(settings.mode(), settings.pacUrl() == null ? null : settings.pacUrl().toString(),
                settings.proxyHost(), settings.proxyPort() == null ? null : String.valueOf(settings.proxyPort()),
                String.join(", ", settings.nonProxyHosts()), settings.resolveTimeout(), settings.cacheTtl(),
                settings.failureTtl());
    }

    /** Ausnahmen als Liste: Komma, Semikolon oder Leerzeichen trennen. */
    public List<String> hosts() {
        return nonProxyHosts == null ? List.of() : Arrays.stream(nonProxyHosts.split("[,;\\s]+"))
                .filter(host -> !host.isBlank()).toList();
    }

    /** Was fehlt oder nicht passt; leer, wenn die Einstellungen so in die Datei können. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (mode == ProxySettings.Mode.PAC_URL_MANUAL) {
            if (pacUrl == null) {
                problems.add("Die PAC-Adresse fehlt.");
            } else if (!pacUrl.matches("(?i)(https?|file)://.+")) {
                problems.add("Die PAC-Adresse beginnt mit http://, https:// oder file://.");
            }
        }
        if (mode == ProxySettings.Mode.MANUAL_PROXY) {
            if (proxyHost == null) {
                problems.add("Der Proxy-Host fehlt.");
            }
            if (port() == null) {
                problems.add("Der Proxy-Port ist eine Zahl von 1 bis 65535.");
            }
        }
        return problems;
    }

    /** Die Einstellungen für den Core; nur gültig, wenn {@link #problems()} leer ist. */
    public ProxySettings settings() {
        boolean pac = mode == ProxySettings.Mode.PAC_URL_MANUAL;
        boolean manual = mode == ProxySettings.Mode.MANUAL_PROXY;
        return new ProxySettings(mode, pac && pacUrl != null ? URI.create(pacUrl) : null, manual ? proxyHost : null,
                manual ? port() : null, hosts(), resolveTimeout, cacheTtl, failureTtl);
    }

    /** Der Block {@code proxy:} mit Einrückung {@code indent}; nur Schlüssel mit Wert. */
    public String yaml(String indent) {
        ProxySettings settings = settings();
        StringBuilder yaml = new StringBuilder(indent).append("proxy:\n");
        String inner = indent + "  ";
        yaml.append(inner).append("mode: ").append(name(mode)).append('\n');
        if (settings.pacUrl() != null) {
            yaml.append(inner).append("pac-url: ").append(quote(settings.pacUrl().toString())).append('\n');
        }
        if (settings.proxyHost() != null) {
            yaml.append(inner).append("proxy-host: ").append(quote(settings.proxyHost())).append('\n');
            yaml.append(inner).append("proxy-port: ").append(settings.proxyPort()).append('\n');
        }
        if (!settings.nonProxyHosts().isEmpty()) {
            yaml.append(inner).append("non-proxy-hosts:\n");
            settings.nonProxyHosts().forEach(host -> yaml.append(inner).append("  - ").append(quote(host)).append('\n'));
        }
        duration(yaml, inner, "resolve-timeout", settings.resolveTimeout(), ProxySettings.DEFAULT_RESOLVE_TIMEOUT);
        duration(yaml, inner, "cache-ttl", settings.cacheTtl(), ProxySettings.DEFAULT_CACHE_TTL);
        duration(yaml, inner, "failure-ttl", settings.failureTtl(), ProxySettings.DEFAULT_FAILURE_TTL);
        return yaml.toString();
    }

    /** {@code pac-url-powershell} für {@link ProxySettings.Mode#PAC_URL_POWERSHELL}, wie in der Datei. */
    public static String name(ProxySettings.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private Integer port() {
        if (proxyPort == null || !proxyPort.matches("\\d{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(proxyPort);
        return port >= 1 && port <= 65535 ? port : null;
    }

    private static void duration(StringBuilder yaml, String indent, String key, Duration value, Duration standard) {
        if (value != null && !value.equals(standard)) {
            yaml.append(indent).append(key).append(": ").append(value.toMillis()).append("ms\n");
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

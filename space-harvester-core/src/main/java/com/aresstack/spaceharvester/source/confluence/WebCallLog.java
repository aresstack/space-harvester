package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Protokolliert jede Adresse außerhalb von {@code /rest/}, die der Confluence-Transport öffnet (#571): Downloads, die
 * Seite der Zwei-Faktor-Anmeldung, das Ziel des Formulars und jede Weiterleitung dazwischen, damit sich der Ablauf mit
 * Benutzer und Passwort und mit Personal Access Token vergleichen lässt. Der Transport fragt für jeden Schritt einer
 * Weiterleitung die Route; darum sieht diese Hülle jede Adresse in der Reihenfolge der Abrufe.
 *
 * <p>Nur Schema, Host, Port, Pfad und die Namen der Query-Parameter, nie deren Werte (Tokens, Sitzungen, Codes), nie
 * Kopfzeilen oder Cookies. REST-Aufrufe bleiben draußen, sie wären zu viele und laufen nicht über die Anmeldeseite.
 */
final class WebCallLog implements HttpRoutePort {

    private static final Logger LOG = LoggerFactory.getLogger(WebCallLog.class);

    private final HttpRoutePort routes;
    private final String label;

    /**
     * @param routes die eigentliche Routenwahl
     * @param label  Quelle und Anmeldeart fürs Protokoll, etwa {@code wiki, PAT}; nicht geheim
     */
    WebCallLog(HttpRoutePort routes, String label) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.label = label == null ? "" : label;
    }

    @Override
    public HttpRoute routeFor(URI target) {
        if (target != null && web(target)) {
            LOG.info("[Confluence {}] Webaufruf {}", label, describe(target));
        }
        return routes.routeFor(target);
    }

    private static boolean web(URI target) {
        String path = target.getRawPath();
        return path == null || !(path.startsWith("/rest/") || path.contains("/rest/api/"));
    }

    /** Adresse ohne Werte: {@code https://host/pfad parameter=[a, b]}. */
    static String describe(URI target) {
        StringBuilder out = new StringBuilder();
        out.append(target.getScheme()).append("://").append(target.getHost());
        if (target.getPort() >= 0) {
            out.append(':').append(target.getPort());
        }
        out.append(target.getRawPath() == null ? "" : target.getRawPath());
        List<String> names = parameterNames(target.getRawQuery());
        if (!names.isEmpty()) {
            out.append(" parameter=").append(names);
        }
        return out.toString();
    }

    private static List<String> parameterNames(String query) {
        List<String> names = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return names;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals >= 0 ? pair.substring(0, equals) : pair;
            if (!name.isEmpty() && name.length() <= 64 && name.matches("[A-Za-z0-9_.\\-\\[\\]%]+")) {
                names.add(name);
            } else if (!name.isEmpty()) {
                names.add("…");
            }
        }
        return names;
    }

    @Override
    public String toString() {
        return "WebCallLog[" + routes + "]";
    }
}

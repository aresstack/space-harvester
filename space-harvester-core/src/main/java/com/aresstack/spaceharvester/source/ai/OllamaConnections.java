package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.util.Objects;

/**
 * Verbindungen zu einem Ollama-Server über dieselbe Proxy-Route (fail-closed), dasselbe Vertrauen und dieselben
 * Fristen wie die übrigen Aufrufe an den KI-Dienst.
 */
final class OllamaConnections {

    private final String server;
    private final HttpRoutePort routes;
    private final SSLSocketFactory tls;
    private final String userAgent;
    private final int timeoutMillis;

    /**
     * @param server Adresse des Servers ohne abschließenden Schrägstrich, etwa {@code http://localhost:11434}
     * @param tls    Vertrauen für {@code https} oder {@code null} für das der JVM
     */
    OllamaConnections(String server, HttpRoutePort routes, SSLSocketFactory tls, String userAgent, int timeoutMillis) {
        this.server = Objects.requireNonNull(server, "server");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.tls = tls;
        this.userAgent = userAgent;
        this.timeoutMillis = timeoutMillis;
    }

    /** Dieselben Verbindungen mit anderer Frist. */
    OllamaConnections withTimeout(int timeoutMillis) {
        return new OllamaConnections(server, routes, tls, userAgent, timeoutMillis);
    }

    /** Eine Verbindung zu {@code path} (etwa {@code /api/chat}) mit Route, Vertrauen, Fristen und User-Agent. */
    HttpURLConnection open(String path) throws IOException {
        URI target = URI.create(server + path);
        HttpRoute route = routes.routeFor(target);
        if (route == null) {
            throw new IOException("no route decision for " + target.getHost());
        }
        Proxy proxy = switch (route.kind()) {
            case DIRECT -> Proxy.NO_PROXY;
            case PROXY -> new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(route.proxyHost(), route.proxyPort()));
            default -> throw new IOException("proxy route for " + target.getHost() + " unavailable");
        };
        URLConnection raw = target.toURL().openConnection(proxy);
        if (!(raw instanceof HttpURLConnection connection)) {
            throw new IOException("not an HTTP endpoint: " + target.getHost());
        }
        if (tls != null && connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(tls);
        }
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        return connection;
    }
}

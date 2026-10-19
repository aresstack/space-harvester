package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

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

/**
 * Öffnet eine {@link HttpURLConnection} mit der Route des {@link HttpRoutePort}s (wie im MediaWiki-Adapter):
 * DIRECT ist {@link Proxy#NO_PROXY}, PROXY ein HTTP-Proxy, UNAVAILABLE eine {@link IOException}; ohne Port gilt
 * der JVM-Standard.
 */
final class RouteConnections {

    private RouteConnections() {
    }

    static HttpURLConnection open(URI target, HttpRoutePort routes, SSLSocketFactory sslSocketFactory,
                                  String userAgent) throws IOException {
        URLConnection raw = routes == null ? target.toURL().openConnection()
                : target.toURL().openConnection(toProxy(target, routes.routeFor(target)));
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("not an HTTP endpoint: " + target.getHost());
        }
        HttpURLConnection connection = (HttpURLConnection) raw;
        if (sslSocketFactory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(sslSocketFactory);
        }
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        return connection;
    }

    private static Proxy toProxy(URI target, HttpRoute route) throws IOException {
        if (route == null) {
            throw new IOException("no route decision for " + target.getHost());
        }
        switch (route.kind()) {
            case DIRECT:
                return Proxy.NO_PROXY;
            case PROXY:
                return new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(route.proxyHost(), route.proxyPort()));
            default:
                throw new IOException("proxy route for " + target.getHost() + " unavailable: " + route.describe());
        }
    }
}

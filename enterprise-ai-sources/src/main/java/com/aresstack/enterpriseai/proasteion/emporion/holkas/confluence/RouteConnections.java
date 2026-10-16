package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

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
 * Öffnet eine {@link HttpURLConnection} mit der Route des {@link HttpRoutePort}s: DIRECT wird ausdrücklich
 * {@link Proxy#NO_PROXY} (kein prozessweiter {@code ProxySelector}), PROXY ein HTTP-Proxy mit unaufgelöster
 * Adresse (den Proxy-Host löst das JDK beim Verbinden auf), UNAVAILABLE eine {@link IOException} mit dem Grund.
 * Ohne Port gilt der JVM-Standard. Dieselbe Hilfsklasse gibt es je Adapter, weil der Port selbst keine
 * {@code java.net.Proxy}-Typen kennen darf.
 */
final class RouteConnections {

    private RouteConnections() {
    }

    static HttpURLConnection open(URI target, HttpRoutePort routes, SSLSocketFactory sslSocketFactory,
                                  String userAgent) throws IOException {
        URLConnection raw;
        if (routes == null) {
            raw = target.toURL().openConnection();
        } else {
            raw = target.toURL().openConnection(toProxy(target, routes.routeFor(target)));
        }
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

    static Proxy toProxy(URI target, HttpRoute route) throws IOException {
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

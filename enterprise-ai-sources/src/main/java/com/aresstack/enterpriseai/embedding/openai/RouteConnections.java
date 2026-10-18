package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;

/**
 * Öffnet die {@link HttpURLConnection} einer Anfrage mit der Route aus der Konfiguration: der
 * {@link HttpRoutePort} entscheidet je Ziel (DIRECT = ausdrücklich {@link Proxy#NO_PROXY}, PROXY = HTTP-Proxy mit
 * unaufgelöster Adresse, UNAVAILABLE = {@link IOException} mit Grund); ohne Port gilt der feste
 * {@link OpenAiCompatibleEmbeddingConfiguration#proxy()} und sonst der JVM-Standard. TLS-Vertrauen und
 * User-Agent werden je Verbindung gesetzt, nie prozessweit.
 */
final class RouteConnections {

    private RouteConnections() {
    }

    static HttpURLConnection open(URI target, OpenAiCompatibleEmbeddingConfiguration configuration)
            throws IOException {
        URLConnection raw;
        if (configuration.routes() != null) {
            raw = target.toURL().openConnection(toProxy(target, configuration.routes().routeFor(target)));
        } else if (configuration.proxy() != null) {
            raw = target.toURL().openConnection(configuration.proxy());
        } else {
            raw = target.toURL().openConnection();
        }
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("not an HTTP endpoint: " + target);
        }
        HttpURLConnection connection = (HttpURLConnection) raw;
        if (configuration.sslSocketFactory() != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(configuration.sslSocketFactory());
        }
        if (configuration.userAgent() != null) {
            connection.setRequestProperty("User-Agent", configuration.userAgent());
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

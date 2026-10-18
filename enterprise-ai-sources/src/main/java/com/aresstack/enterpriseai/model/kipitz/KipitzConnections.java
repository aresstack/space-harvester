package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;

/** Öffnet Verbindungen zur Enterprise-API mit Route, TLS und User-Agent aus der {@link KipitzModelCatalogConfig}. */
final class KipitzConnections {

    private KipitzConnections() {
    }

    static HttpURLConnection open(KipitzModelCatalogConfig config, URI target) throws IOException {
        URLConnection raw;
        if (config.routes() == null) {
            raw = target.toURL().openConnection();
        } else {
            raw = target.toURL().openConnection(toProxy(target, config.routes().routeFor(target)));
        }
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("not an HTTP endpoint: " + target.getHost());
        }
        HttpURLConnection connection = (HttpURLConnection) raw;
        if (config.sslSocketFactory() != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(config.sslSocketFactory());
        }
        if (config.userAgent() != null) {
            connection.setRequestProperty("User-Agent", config.userAgent());
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

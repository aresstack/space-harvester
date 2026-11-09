package com.aresstack.spaceharvester.app.nlp;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * Lädt eine Datei über denselben Netzweg wie die Quellen: Proxy-Regel aus den Netzwerk-Einstellungen
 * ({@link ProxyRoutes}, auch PAC), Anmeldung am Proxy und die TLS-Vertrauensregel der App. Folgt Weiterleitungen
 * (GitHub leitet Release-Downloads um). Meldet nie Zugangsdaten.
 */
public final class HttpModelDownload implements ParserModelInstaller.Download {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final ProxySettings proxy;
    private final TrustSettings tls;
    private final String userAgent;

    /**
     * @param proxy     Proxy-Einstellungen samt Passwort aus Tresor oder Sitzung
     * @param tls       Vertrauensregel der App
     * @param userAgent etwa {@code space-harvester-app/1.0}
     */
    public HttpModelDownload(ProxySettings proxy, TrustSettings tls, String userAgent) {
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        this.proxy = proxy;
        this.tls = tls;
    }

    /** Baut den Client erst beim Laden, damit reine Statusabfragen nichts aufbauen. */
    private HttpClient client() {
        ProxyRoutes routes = ProxyRoutes.from(proxy == null ? ProxySettings.disabled() : proxy);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(new RouteSelector(routes));
        if (proxy != null && proxy.authentication() != null && proxy.authentication().password() != null) {
            com.aresstack.spaceharvester.source.network.ProxyAuthenticator.allowBasicTunneling();
            String user = proxy.authentication().username();
            char[] password = proxy.authentication().password().clone();
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY
                            ? new PasswordAuthentication(user, password.clone()) : null;
                }
            });
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {TrustPolicy.defaultTrust(tls)}, null);
            builder.sslContext(context);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS nicht einrichtbar", e);
        }
        return builder.build();
    }

    @Override
    public ParserModelInstaller.Body open(URI target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(target).header("User-Agent", userAgent).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download unterbrochen", e);
        }
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("Download fehlgeschlagen: HTTP " + response.statusCode() + " von "
                    + response.uri().getHost());
        }
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        return new ParserModelInstaller.Body(response.body(), length);
    }

    /** Fragt je Ziel die Proxy-Regel der App. */
    private static final class RouteSelector extends ProxySelector {

        private final ProxyRoutes routes;

        RouteSelector(ProxyRoutes routes) {
            this.routes = routes;
        }

        @Override
        public List<Proxy> select(URI uri) {
            HttpRoute route = routes.routeFor(uri);
            if (route.isProxy()) {
                return List.of(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(route.proxyHost(), route.proxyPort())));
            }
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            routes.invalidate();
        }
    }
}

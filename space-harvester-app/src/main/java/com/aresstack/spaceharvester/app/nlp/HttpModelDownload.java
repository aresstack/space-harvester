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
public final class HttpModelDownload implements ParserModelInstaller.Download,
        com.aresstack.spaceharvester.app.hf.HuggingFaceClient.Transport {


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
        return routed(proxy, tls).followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(com.aresstack.spaceharvester.app.ui.AppTimeouts.shared().get(com.aresstack.spaceharvester.app.ui.AppTimeouts.Value.DOWNLOAD_CONNECT)).build();
    }

    /**
     * Ein {@link HttpClient.Builder} mit dem Netzweg der App: Proxy-Regel je Ziel ({@link ProxyRoutes}, auch PAC; ohne
     * gültige Regel nie direkt), Anmeldung am Proxy und die TLS-Vertrauensregel. Weiterleitungen und Zeitlimits setzt
     * der Aufrufer. Auch für das Command Deck (BetaView), damit es denselben Weg nimmt wie die Quellen.
     *
     * @param proxy Proxy-Einstellungen samt Passwort aus Tresor oder Sitzung, {@code null} für keinen Proxy
     * @param tls   Vertrauensregel der App
     */
    public static HttpClient.Builder routed(ProxySettings proxy, TrustSettings tls) {
        ProxyRoutes routes = ProxyRoutes.from(proxy == null ? ProxySettings.disabled() : proxy);
        HttpClient.Builder builder = HttpClient.newBuilder().proxy(new RouteSelector(routes));
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
        return builder;
    }

    /** Für Hugging Face: jede Antwort samt Status, ab {@code rangeFrom} zum Fortsetzen eines Downloads. */
    @Override
    public com.aresstack.spaceharvester.app.hf.HuggingFaceClient.Response get(URI target, long rangeFrom)
            throws IOException {
        HttpResponse<InputStream> response = send(target, rangeFrom);
        return new com.aresstack.spaceharvester.app.hf.HuggingFaceClient.Response(response.statusCode(),
                response.body(), response.headers().firstValueAsLong("Content-Length").orElse(-1L),
                response.headers().firstValue("Content-Range").orElse(null), response.uri().getHost());
    }

    private HttpResponse<InputStream> send(URI target, long rangeFrom) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(target).header("User-Agent", userAgent)
                // Höchstens so lange auf die Antwortköpfe warten (Einstellungen › Allgemein); ein hängender Datenstrom
                // endet über „Abbrechen“.
                .timeout(com.aresstack.spaceharvester.app.ui.AppTimeouts.shared().get(com.aresstack.spaceharvester.app.ui.AppTimeouts.Value.DOWNLOAD_RESPONSE)).GET();
        if (rangeFrom > 0) {
            request.header("Range", "bytes=" + rangeFrom + "-");
        }
        try {
            return client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download unterbrochen", e);
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public ParserModelInstaller.Body open(URI target) throws IOException {
        HttpResponse<InputStream> response = send(target, 0);
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
            if (route.isUnavailable()) {
                // Wie RouteConnections: ohne gültige Proxy-Regel (PAC gescheitert) nie direkt verbinden.
                throw new java.io.UncheckedIOException(new IOException("Kein Netzweg zu " + uri.getHost() + ": "
                        + route.describe()));
            }
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

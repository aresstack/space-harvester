package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;

/**
 * {@link ConfluenceHttpTransport} über {@link HttpURLConnection} (Java 8, keine Zusatzbibliothek), wie
 * MainframeMate {@code ConfluenceRestClient}.
 *
 * <p>Proxy und TLS werden von außen hereingereicht (Composition Root): die Proxy-Route je Ziel über den
 * {@link HttpRoutePort} (Vorrang) oder ein fester {@link Proxy}; die {@link SSLSocketFactory} trägt die
 * Vertrauensquellen und gegebenenfalls das Client-Zertifikat ({@link ClientCertificates}). Der Adapter liest
 * keine globalen Proxy-Einstellungen und setzt nichts prozessweit. Weiterleitungen werden nicht verfolgt, damit ein
 * {@code Authorization}-Header nie an ein anderes Ziel geht; Confluence DC leitet nicht angemeldete Zugriffe auf
 * die Login-Seite um, das meldet der Adapter als verweigerten Zugriff.
 */
public final class UrlConnectionConfluenceTransport implements ConfluenceHttpTransport {

    private final Proxy proxy;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final SSLSocketFactory anonymousSslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final java.util.concurrent.atomic.AtomicBoolean factoryLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(UrlConnectionConfluenceTransport.class.getName());

    private UrlConnectionConfluenceTransport(Builder builder) {
        this.proxy = builder.proxy;
        this.routes = builder.routes;
        this.sslSocketFactory = builder.sslSocketFactory;
        this.anonymousSslSocketFactory = builder.anonymousSslSocketFactory;
        this.userAgent = builder.userAgent;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Folgt Weiterleitungen wie MainframeMate ({@code setInstanceFollowRedirects(true)}), aber nur auf demselben
     * Host und ohne Wechsel von https auf http, damit der Anmelde-Header den Server nicht verlässt; höchstens
     * fünf Schritte. Andere Weiterleitungen (z. B. auf eine Anmeldeseite) kommen als Antwort zurück.
     */
    @Override
    public ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) throws IOException {
        return get(uri, headers, maxBytes, sslSocketFactory);
    }

    /** Ohne Client-Zertifikat: nur die Vertrauensquellen ({@link Builder#anonymousSslSocketFactory}). */
    @Override
    public ConfluenceHttpResponse getAnonymous(URI uri, Map<String, String> headers, int maxBytes)
            throws IOException {
        return get(uri, headers, maxBytes, anonymousSslSocketFactory);
    }

    private ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes, SSLSocketFactory tls)
            throws IOException {
        return exchange("GET", uri, headers, null, maxBytes, tls);
    }

    @Override
    public ConfluenceHttpResponse postForm(URI uri, Map<String, String> headers, byte[] form, int maxBytes)
            throws IOException {
        return exchange("POST", uri, headers, form == null ? new byte[0] : form, maxBytes, sslSocketFactory);
    }

    /**
     * Ein Request mit Weiterleitungen auf demselben Host wie ein Browser: Cookies, die ein Schritt setzt, gehen bei
     * den folgenden mit (zusätzlich zum {@code Cookie}-Header des Aufrufers, gleiche Namen ersetzt); nach 301/302/303
     * geht es mit GET weiter, nach 307/308 mit derselben Methode.
     */
    private ConfluenceHttpResponse exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                                            int maxBytes, SSLSocketFactory tls) throws IOException {
        URI current = uri;
        String currentMethod = method;
        byte[] currentBody = body;
        Map<String, String> cookies = new java.util.LinkedHashMap<String, String>();
        java.util.List<String> redirects = new java.util.ArrayList<String>();
        for (int hop = 0; ; hop++) {
            String[] location = new String[1];
            ConfluenceHttpResponse response = once(currentMethod, current,
                    hop == 0 ? headers : withCookies(headers, cookies), currentBody, maxBytes, location, tls);
            merge(cookies, response.cookies());
            URI next = hop < ConfluenceRedirects.MAX_REDIRECTS ? sameHostRedirect(current, response.status(), location[0]) : null;
            if (next == null) {
                return new ConfluenceHttpResponse(response.status(), response.contentType(), response.body(),
                        response.location(), pairs(cookies), current, redirects);
            }
            if (response.status() != 307 && response.status() != 308) {
                currentMethod = "GET";
                currentBody = null;
            }
            redirects.add(next.toString());
            current = next;
        }
    }

    /** Die Header mit den unterwegs gesetzten Cookies im {@code Cookie}-Header (gleiche Namen ersetzt). */
    static Map<String, String> withCookies(Map<String, String> headers, Map<String, String> collected) {
        return ConfluenceRedirects.withCookies(headers, collected);
    }

    static void merge(Map<String, String> cookies, java.util.List<String> pairs) {
        ConfluenceRedirects.merge(cookies, pairs);
    }

    private static java.util.List<String> pairs(Map<String, String> cookies) {
        return ConfluenceRedirects.pairs(cookies);
    }

    static URI sameHostRedirect(URI from, int status, String location) {
        return ConfluenceRedirects.sameHostRedirect(from, status, location);
    }

    private ConfluenceHttpResponse once(String method, URI uri, Map<String, String> headers, byte[] body,
                                        int maxBytes, String[] location, SSLSocketFactory tls) throws IOException {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("nur http/https unterstützt: " + scheme);
        }
        HttpURLConnection connection = open(uri, tls);
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            location[0] = connection.getHeaderField("Location");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] received = stream == null ? new byte[0] : readLimited(stream, maxBytes);
            return new ConfluenceHttpResponse(status, connection.getContentType(), received, location[0],
                    setCookies(connection.getHeaderFields()));
        } finally {
            connection.disconnect();
        }
    }

    /** Siehe {@link ConfluenceRedirects#setCookies(Map)}. */
    static java.util.List<String> setCookies(Map<String, java.util.List<String>> headerFields) {
        return ConfluenceRedirects.setCookies(headerFields);
    }

    private HttpURLConnection open(URI uri, SSLSocketFactory tls) throws IOException {
        if (tls != null && tls == sslSocketFactory && "https".equalsIgnoreCase(uri.getScheme())
                && factoryLogged.compareAndSet(false, true)) {
            LOG.info("[Confluence] TLS für " + uri.getHost() + ": " + sslSocketFactory);
        }
        if (routes != null) {
            return RouteConnections.open(uri, routes, tls, userAgent);
        }
        HttpURLConnection connection = (HttpURLConnection) (proxy == null
                ? uri.toURL().openConnection()
                : uri.toURL().openConnection(proxy));
        if (tls != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(tls);
        }
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        return connection;
    }

    private static byte[] readLimited(InputStream stream, int maxBytes) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes) {
                    throw new ConfluenceResponseTooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public String toString() {
        return "UrlConnectionConfluenceTransport[proxy=" + (routes != null ? routes : proxy == null ? "direkt" : proxy)
                + ", tls=" + (sslSocketFactory != null) + "]";
    }

    /** Builder; Defaults: direkte Verbindung, Standard-TLS, 15 s Verbindungs- und 60 s Lese-Timeout. */
    public static final class Builder {

        private Proxy proxy;
        private HttpRoutePort routes;
        private SSLSocketFactory sslSocketFactory;
        private SSLSocketFactory anonymousSslSocketFactory;
        private String userAgent;
        private int connectTimeoutMillis = 15000;
        private int readTimeoutMillis = 60000;

        private Builder() {
        }

        /** Fester Proxy; {@code null} = direkt. Ein gesetzter {@link #routes(HttpRoutePort)} hat Vorrang. */
        public Builder proxy(Proxy value) {
            this.proxy = value;
            return this;
        }

        /** Proxy-Route je Ziel aus der Composition Root; {@code null} = {@link #proxy(Proxy)} bzw. direkt. */
        public Builder routes(HttpRoutePort value) {
            this.routes = value;
            return this;
        }

        /**
         * TLS-Fabrik mit den Vertrauensquellen der Anwendung und gegebenenfalls Client-Zertifikat, z. B.
         * {@link ClientCertificates#windowsMy(String, javax.net.ssl.X509TrustManager)}; {@code null} = JVM-Standard.
         */
        public Builder sslSocketFactory(SSLSocketFactory value) {
            this.sslSocketFactory = value;
            return this;
        }

        /**
         * TLS-Fabrik für Ziele außerhalb der eingestellten Confluence ({@link #getAnonymous}): Vertrauensquellen
         * der Anwendung, nie ein Client-Zertifikat; {@code null} = JVM-Standard.
         */
        public Builder anonymousSslSocketFactory(SSLSocketFactory value) {
            this.anonymousSslSocketFactory = value;
            return this;
        }

        /** {@code User-Agent}-Header; {@code null} oder leer = JVM-Standard. */
        public Builder userAgent(String value) {
            this.userAgent = value == null || value.trim().isEmpty() ? null : value.trim();
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            this.connectTimeoutMillis = positive(value, "connectTimeoutMillis");
            return this;
        }

        public Builder readTimeoutMillis(int value) {
            this.readTimeoutMillis = positive(value, "readTimeoutMillis");
            return this;
        }

        public UrlConnectionConfluenceTransport build() {
            return new UrlConnectionConfluenceTransport(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " muss positiv sein");
            }
            return value;
        }
    }
}

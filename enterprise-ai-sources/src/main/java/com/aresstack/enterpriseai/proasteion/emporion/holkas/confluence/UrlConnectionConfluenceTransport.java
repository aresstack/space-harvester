package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.proasteion.platform.network.ResumableDownload;

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
 *
 * <p>Reißt ein Download ({@link #download}) ab, setzt der Transport ihn mit {@link ResumableDownload} ab dem letzten
 * Byte fort, höchstens {@link Builder#resumes(int)} Mal je Abruf (Angelo 26.11.2026, WLAN).
 */
public final class UrlConnectionConfluenceTransport implements ConfluenceHttpTransport {

    private final Proxy proxy;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final SSLSocketFactory anonymousSslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int resumes;
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
        this.resumes = builder.resumes;
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
        return exchange("GET", uri, headers, null, maxBytes, tls, null);
    }

    /** Ein strombarer Körper geht ohne Grenze direkt aus der Verbindung nach {@code sink}, nie ganz in den Speicher. */
    @Override
    public ConfluenceHttpResponse download(URI uri, Map<String, String> headers, java.io.OutputStream sink,
                                           int pageMaxBytes) throws IOException {
        return exchange("GET", uri, headers, null, pageMaxBytes, sslSocketFactory, sink);
    }

    @Override
    public ConfluenceHttpResponse downloadAnonymous(URI uri, Map<String, String> headers, java.io.OutputStream sink,
                                                    int pageMaxBytes) throws IOException {
        return exchange("GET", uri, headers, null, pageMaxBytes, anonymousSslSocketFactory, sink);
    }

    /**
     * Ziel von {@link #postForm} für einen strombaren Körper (Erfolg, kein HTML): wird nicht gelesen. Nach einem
     * angenommenen Einmalcode leitet Confluence auf die Datei weiter, die die Code-Seite ausgelöst hat; ein großer
     * Anhang überschritt dabei die Grenze für Antworten, und das Absenden galt als gescheitert, obwohl der Code
     * angenommen war (Space Harvester, 16.11.2026).
     */
    private static final java.io.OutputStream SKIP_BODY = new java.io.OutputStream() {
        @Override
        public void write(int b) {
            throw new IllegalStateException("SKIP_BODY wird nie beschrieben");
        }
    };

    @Override
    public ConfluenceHttpResponse postForm(URI uri, Map<String, String> headers, byte[] form, int maxBytes)
            throws IOException {
        return exchange("POST", uri, headers, form == null ? new byte[0] : form, maxBytes, sslSocketFactory,
                SKIP_BODY);
    }

    /**
     * Ein Request mit Weiterleitungen auf demselben Host wie ein Browser: Cookies, die ein Schritt setzt, gehen bei
     * den folgenden mit (zusätzlich zum {@code Cookie}-Header des Aufrufers, gleiche Namen ersetzt); nach 301/302/303
     * geht es mit GET weiter, nach 307/308 mit derselben Methode.
     */
    private ConfluenceHttpResponse exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                                            int maxBytes, SSLSocketFactory tls, java.io.OutputStream sink)
            throws IOException {
        URI current = uri;
        String currentMethod = method;
        byte[] currentBody = body;
        Map<String, String> cookies = new java.util.LinkedHashMap<String, String>();
        java.util.List<String> redirects = new java.util.ArrayList<String>();
        for (int hop = 0; ; hop++) {
            String[] location = new String[1];
            ConfluenceHttpResponse response = once(currentMethod, current,
                    hop == 0 ? headers : withCookies(headers, cookies), currentBody, maxBytes, location, tls, sink);
            merge(cookies, response.cookies());
            URI next = hop < ConfluenceRedirects.MAX_REDIRECTS ? sameHostRedirect(current, response.status(), location[0]) : null;
            if (next == null) {
                ConfluenceHttpResponse last = new ConfluenceHttpResponse(response.status(), response.contentType(),
                        response.body(), response.location(), pairs(cookies), current, redirects);
                return response.isStreamed() ? last.streamed(response.streamedBytes()) : last;
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
                                        int maxBytes, String[] location, SSLSocketFactory tls,
                                        java.io.OutputStream sink) throws IOException {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("nur http/https unterstützt: " + scheme);
        }
        HttpURLConnection connection = prepared(method, uri, headers, tls);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                // Kein setFixedLengthStreamingMode: gestreamt kann HttpURLConnection nach einer Anmeldeaufforderung
                // (407 vom Proxy ohne Tunnel, 401) nicht wiederholen und liefert die 407 als Antwort (#571). Das
                // Formular ist klein; gepuffert sendet das JDK es nach der Anmeldung erneut.
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            location[0] = connection.getHeaderField("Location");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (sink != null && stream != null
                    && ConfluenceHttpResponse.streamable(status, connection.getContentType())) {
                if (sink == SKIP_BODY) {
                    // Status, Typ und Cookies reichen; die Verbindung schließt ohne den Rest der Datei.
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // Der Rest der Datei wird nicht gebraucht; disconnect() schließt die Verbindung ohnehin.
                    }
                    return new ConfluenceHttpResponse(status, connection.getContentType(), new byte[0], location[0],
                            setCookies(connection.getHeaderFields()));
                }
                long copied = "GET".equals(method) && status == 200
                        ? resumed(connection, stream, uri, headers, tls, sink)
                        : copy(stream, sink);
                return new ConfluenceHttpResponse(status, connection.getContentType(), new byte[0], location[0],
                        setCookies(connection.getHeaderFields())).streamed(copied);
            }
            byte[] received = stream == null ? new byte[0] : readLimited(stream, maxBytes);
            return new ConfluenceHttpResponse(status, connection.getContentType(), received, location[0],
                    setCookies(connection.getHeaderFields()));
        } finally {
            connection.disconnect();
        }
    }

    /** Eine Verbindung mit Methode, Zeitgrenzen und Kopfzeilen, noch nicht abgeschickt. */
    private HttpURLConnection prepared(String method, URI uri, Map<String, String> headers, SSLSocketFactory tls)
            throws IOException {
        HttpURLConnection connection = open(uri, tls);
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setUseCaches(false);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    /**
     * Kopiert einen Download nach {@code sink} und fordert nach einem Abriss den Rest mit denselben Kopfzeilen ab dem
     * letzten Byte an ({@link ResumableDownload}).
     */
    private long resumed(HttpURLConnection connection, InputStream stream, final URI uri,
                         final Map<String, String> headers, final SSLSocketFactory tls, java.io.OutputStream sink)
            throws IOException {
        ResumableDownload.Response first = response(connection, stream);
        return ResumableDownload.copy(first, new ResumableDownload.Source() {
            @Override
            public ResumableDownload.Response open(long from, String validator) throws IOException {
                final HttpURLConnection rest = prepared("GET", uri, headers, tls);
                for (Map.Entry<String, String> header : ResumableDownload.rangeHeaders(from, validator).entrySet()) {
                    rest.setRequestProperty(header.getKey(), header.getValue());
                }
                int status;
                try {
                    status = rest.getResponseCode();
                } catch (IOException e) {
                    rest.disconnect();
                    throw e;
                }
                InputStream body = status >= 400 ? rest.getErrorStream() : rest.getInputStream();
                ResumableDownload.Response response = response(rest, body);
                return new ResumableDownload.Response(response.status(), response.contentRange(),
                        response.validator(), response.body(), response.length()) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            rest.disconnect();
                        }
                    }
                };
            }
        }, ResumableDownload.sink(sink), Long.MAX_VALUE, resumes);
    }

    private static ResumableDownload.Response response(final HttpURLConnection connection, InputStream body)
            throws IOException {
        return ResumableDownload.response(connection.getResponseCode(), new ResumableDownload.Headers() {
            @Override
            public String header(String name) {
                return connection.getHeaderField(name);
            }
        }, body);
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

    private static long copy(InputStream stream, java.io.OutputStream sink) throws IOException {
        try (InputStream in = stream) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
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
        private int resumes;

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

        /** Wie oft ein abgerissener Download je Abruf höchstens fortgesetzt wird; 0 (Standard) für nie. */
        public Builder resumes(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("resumes darf nicht negativ sein");
            }
            this.resumes = value;
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

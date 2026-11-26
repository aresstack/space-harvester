package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.proasteion.platform.network.ResumableDownload;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/**
 * {@link JiraHttpTransport} über {@link HttpURLConnection} (Java 8, keine Zusatzbibliothek), wie
 * {@code UrlConnectionConfluenceTransport}.
 *
 * <p>Proxy und TLS werden von außen hereingereicht (Composition Root): die Proxy-Route je Ziel über den
 * {@link HttpRoutePort}; die {@link SSLSocketFactory} trägt die Vertrauensquellen und gegebenenfalls das
 * Client-Zertifikat. Der Adapter liest keine globalen Proxy-Einstellungen und setzt nichts prozessweit.
 * Weiterleitungen werden nicht verfolgt, damit ein {@code Authorization}-Header nie an ein anderes Ziel geht; Jira
 * leitet nicht angemeldete Zugriffe auf die Anmeldeseite um, das meldet der Adapter als verweigerten Zugriff.
 *
 * <p>Reißt eine Antwort (etwa ein Anhang) beim Lesen ab, setzt der Transport sie mit {@link ResumableDownload} ab dem
 * letzten Byte fort, höchstens {@link Builder#resumes(int)} Mal je Abruf (Angelo 26.11.2026, WLAN).
 */
public final class UrlConnectionJiraTransport implements JiraHttpTransport {

    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int resumes;

    private UrlConnectionJiraTransport(Builder builder) {
        this.routes = builder.routes;
        this.sslSocketFactory = builder.sslSocketFactory;
        this.userAgent = builder.userAgent;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.resumes = builder.resumes;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public JiraHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) throws IOException {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("nur http/https unterstützt: " + scheme);
        }
        HttpURLConnection connection = prepared(uri, headers);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] received = stream == null ? new byte[0]
                    : status == 200 ? resumed(connection, stream, uri, headers, maxBytes)
                    : readLimited(stream, maxBytes);
            return new JiraHttpResponse(status, connection.getContentType(), received);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection prepared(URI uri, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = open(uri);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setUseCaches(false);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    /** Liest die Antwort und fordert nach einem Abriss den Rest mit denselben Kopfzeilen ab dem letzten Byte an. */
    private byte[] resumed(HttpURLConnection connection, InputStream stream, final URI uri,
                           final Map<String, String> headers, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ResumableDownload.copy(response(connection, stream), new ResumableDownload.Source() {
                @Override
                public ResumableDownload.Response open(long from, String validator) throws IOException {
                    final HttpURLConnection rest = prepared(uri, headers);
                    for (Map.Entry<String, String> header
                            : ResumableDownload.rangeHeaders(from, validator).entrySet()) {
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
            }, ResumableDownload.sink(out), maxBytes, resumes);
        } catch (ResumableDownload.TooLargeException e) {
            throw new JiraResponseTooLargeException(maxBytes);
        }
        return out.toByteArray();
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

    @Override
    public JiraHttpResponse post(URI uri, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("nur http/https unterstützt: " + scheme);
        }
        HttpURLConnection connection = open(uri);
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            // Jira Data Center lehnt schreibende Aufrufe ohne diesen Header teils als XSRF ab.
            connection.setRequestProperty("X-Atlassian-Token", "no-check");
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] received = stream == null ? new byte[0] : readLimited(stream, maxBytes);
            return new JiraHttpResponse(status, connection.getContentType(), received);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(URI uri) throws IOException {
        if (routes != null) {
            return RouteConnections.open(uri, routes, sslSocketFactory, userAgent);
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        if (sslSocketFactory != null && connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(sslSocketFactory);
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
                    throw new JiraResponseTooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public String toString() {
        return "UrlConnectionJiraTransport[proxy=" + (routes != null ? routes : "direkt")
                + ", tls=" + (sslSocketFactory != null) + "]";
    }

    /** Builder; Defaults: direkte Verbindung, Standard-TLS, 15 s Verbindungs- und 60 s Lese-Timeout. */
    public static final class Builder {

        private HttpRoutePort routes;
        private SSLSocketFactory sslSocketFactory;
        private String userAgent;
        private int connectTimeoutMillis = 15000;
        private int readTimeoutMillis = 60000;
        private int resumes;

        private Builder() {
        }

        /** Proxy-Route je Ziel aus der Composition Root; {@code null} = direkt. */
        public Builder routes(HttpRoutePort value) {
            this.routes = value;
            return this;
        }

        /** TLS-Fabrik mit Vertrauensquellen und gegebenenfalls Client-Zertifikat; {@code null} = JVM-Standard. */
        public Builder sslSocketFactory(SSLSocketFactory value) {
            this.sslSocketFactory = value;
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

        /** Wie oft eine abgerissene Antwort je Abruf höchstens fortgesetzt wird; 0 (Standard) für nie. */
        public Builder resumes(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("resumes darf nicht negativ sein");
            }
            this.resumes = value;
            return this;
        }

        public UrlConnectionJiraTransport build() {
            return new UrlConnectionJiraTransport(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " muss positiv sein");
            }
            return value;
        }
    }
}

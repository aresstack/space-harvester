package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

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
 */
public final class UrlConnectionJiraTransport implements JiraHttpTransport {

    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private UrlConnectionJiraTransport(Builder builder) {
        this.routes = builder.routes;
        this.sslSocketFactory = builder.sslSocketFactory;
        this.userAgent = builder.userAgent;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
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
        HttpURLConnection connection = open(uri);
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] received = stream == null ? new byte[0] : readLimited(stream, maxBytes);
            return new JiraHttpResponse(status, connection.getContentType(), received);
        } finally {
            connection.disconnect();
        }
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

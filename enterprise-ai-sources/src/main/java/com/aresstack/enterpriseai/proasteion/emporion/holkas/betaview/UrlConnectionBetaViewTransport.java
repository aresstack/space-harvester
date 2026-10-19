package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** {@link BetaViewTransport} über {@link HttpURLConnection} (der eine HTTP-Client der Anwendung). */
final class UrlConnectionBetaViewTransport implements BetaViewTransport {

    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    UrlConnectionBetaViewTransport(HttpRoutePort routes, SSLSocketFactory sslSocketFactory, String userAgent,
                                   int connectTimeoutMillis, int readTimeoutMillis) {
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.userAgent = userAgent;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Response exchange(URI uri, String cookieHeader, String formBody, long maxBytes) throws IOException {
        HttpURLConnection connection = RouteConnections.open(uri, routes, sslSocketFactory, userAgent);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                connection.setRequestProperty("Cookie", cookieHeader);
            }
            if (formBody == null) {
                connection.setRequestMethod("GET");
            } else {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(formBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream in;
            try {
                in = connection.getInputStream();
            } catch (IOException e) {
                in = connection.getErrorStream();
            }
            return new Response(status, connection.getHeaderFields(), read(in, maxBytes));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] read(InputStream in, long maxBytes) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = input.read(buffer)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("response larger than " + maxBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}

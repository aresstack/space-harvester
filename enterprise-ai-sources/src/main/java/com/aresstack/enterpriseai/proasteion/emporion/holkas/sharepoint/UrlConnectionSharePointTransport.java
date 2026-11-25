package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * {@link SharePointTransport} über {@link HttpURLConnection} (der eine HTTP-Client der Anwendung). Ohne
 * Authorization-Header bleibt die Anmeldung dem JDK überlassen (unter Windows NTLM/Kerberos mit der
 * Windows-Anmeldung, sofern das JDK das für den Host erlaubt).
 */
final class UrlConnectionSharePointTransport implements SharePointTransport {

    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    UrlConnectionSharePointTransport(HttpRoutePort routes, SSLSocketFactory sslSocketFactory, String userAgent,
                                     int connectTimeoutMillis, int readTimeoutMillis) {
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.userAgent = userAgent;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Response get(URI uri, String accept, String authorization, long maxBytes) throws IOException {
        HttpURLConnection connection = open(uri, accept, authorization);
        try {
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, connection.getContentType(), read(in, maxBytes));
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(URI uri, String accept, String authorization) throws IOException {
        HttpURLConnection connection = RouteConnections.open(uri, routes, sslSocketFactory, userAgent);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        if (accept != null) connection.setRequestProperty("Accept", accept);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        return connection;
    }

    @Override
    public Response download(URI uri, String authorization, java.nio.file.Path target, long maxBytes) throws IOException {
        HttpURLConnection connection = open(uri, null, authorization);
        try {
            int status = connection.getResponseCode();
            if (status == 200) {
                try (InputStream input = connection.getInputStream();
                     java.io.OutputStream output = java.nio.file.Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    long remaining = maxBytes;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Download interrupted");
                        if (count > remaining) throw new IOException("Download exceeds expected size");
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
            }
            return new Response(status, connection.getContentType(), null);
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

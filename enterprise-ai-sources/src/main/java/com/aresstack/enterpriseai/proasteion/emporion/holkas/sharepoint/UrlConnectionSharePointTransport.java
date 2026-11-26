package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.proasteion.platform.network.ResumableDownload;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * {@link SharePointTransport} über {@link HttpURLConnection} (der eine HTTP-Client der Anwendung). Ohne
 * Authorization-Header bleibt die Anmeldung dem JDK überlassen (unter Windows NTLM/Kerberos mit der
 * Windows-Anmeldung, sofern das JDK das für den Host erlaubt). Reißt ein Inhalt oder Download ab, setzt der
 * Transport ihn mit {@link ResumableDownload} ab dem letzten Byte fort, höchstens {@code resumes} Mal je Abruf.
 */
final class UrlConnectionSharePointTransport implements SharePointTransport {

    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private final String userAgent;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int resumes;

    UrlConnectionSharePointTransport(HttpRoutePort routes, SSLSocketFactory sslSocketFactory, String userAgent,
                                     int connectTimeoutMillis, int readTimeoutMillis) {
        this(routes, sslSocketFactory, userAgent, connectTimeoutMillis, readTimeoutMillis, 0);
    }

    /** @param resumes wie oft ein abgerissener Inhalt je Abruf höchstens fortgesetzt wird, 0 für nie */
    UrlConnectionSharePointTransport(HttpRoutePort routes, SSLSocketFactory sslSocketFactory, String userAgent,
                                     int connectTimeoutMillis, int readTimeoutMillis, int resumes) {
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
        this.userAgent = userAgent;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.resumes = resumes;
    }

    @Override
    public Response get(URI uri, String accept, String authorization, long maxBytes) throws IOException {
        HttpURLConnection connection = open(uri, accept, authorization);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                return new Response(status, connection.getContentType(), read(in, maxBytes));
            }
            String contentType = connection.getContentType();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            ResumableDownload.copy(response(connection), rest(uri, accept, authorization),
                    new ResumableDownload.Sink() {
                        @Override
                        public void write(byte[] buffer, int offset, int length) {
                            out.write(buffer, offset, length);
                        }

                        @Override
                        public void restart() {
                            out.reset();
                        }
                    }, maxBytes, resumes);
            return new Response(status, contentType, out.toByteArray());
        } catch (ResumableDownload.TooLargeException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            connection.disconnect();
        }
    }

    /** Die Antwort einer offenen Verbindung für {@link ResumableDownload}. */
    private static ResumableDownload.Response response(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        String validator = connection.getHeaderField("ETag");
        if (validator == null) {
            validator = connection.getHeaderField("Last-Modified");
        }
        InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String contentRange = connection.getHeaderField("Content-Range");
        long length = status == 206 ? -1 : connection.getContentLengthLong();
        return new ResumableDownload.Response(status, contentRange, validator, body, length);
    }

    /** Folgeanfragen ab einem Byte, mit derselben Anmeldung. */
    private ResumableDownload.Source rest(final URI uri, final String accept, final String authorization) {
        return new ResumableDownload.Source() {
            @Override
            public ResumableDownload.Response open(long from, String validator) throws IOException {
                HttpURLConnection connection = UrlConnectionSharePointTransport.this.open(uri, accept, authorization);
                connection.setRequestProperty("Range", "bytes=" + from + "-");
                if (validator != null) {
                    connection.setRequestProperty("If-Range", validator);
                }
                final HttpURLConnection opened = connection;
                ResumableDownload.Response response = response(connection);
                return new ResumableDownload.Response(response.status(), response.contentRange(),
                        response.validator(), response.body(), response.length()) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            opened.disconnect();
                        }
                    }
                };
            }
        };
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
    public Response post(URI uri, String authorization, String digest, byte[] body, long maxBytes) throws IOException {
        HttpURLConnection connection = open(uri, "application/json;odata=verbose", authorization);
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json;odata=verbose");
            if (digest != null) connection.setRequestProperty("X-RequestDigest", digest);
            // Retain buffered request mode for integrated-authentication retries.
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, connection.getContentType(), read(in, maxBytes),
                    connection.getHeaderFieldDate("Date", 0));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public Response download(URI uri, String authorization, java.nio.file.Path target, long maxBytes) throws IOException {
        HttpURLConnection connection = open(uri, null, authorization);
        try {
            int status = connection.getResponseCode();
            if (status == 200) {
                final java.nio.channels.FileChannel output = java.nio.channels.FileChannel.open(target,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    ResumableDownload.copy(response(connection), rest(uri, null, authorization),
                            new ResumableDownload.Sink() {
                                @Override
                                public void write(byte[] buffer, int offset, int length) throws IOException {
                                    if (Thread.currentThread().isInterrupted()) {
                                        throw new java.io.InterruptedIOException("Download interrupted");
                                    }
                                    java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(buffer, offset, length);
                                    while (bytes.hasRemaining()) {
                                        output.write(bytes);
                                    }
                                }

                                @Override
                                public void restart() throws IOException {
                                    output.truncate(0);
                                    output.position(0);
                                }
                            }, maxBytes, resumes);
                } catch (ResumableDownload.TooLargeException e) {
                    throw new IOException("Download exceeds expected size", e);
                } finally {
                    output.close();
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

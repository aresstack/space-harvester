package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Produktiver Transport über {@link HttpURLConnection} (JDK, Java 8).
 *
 * <p>Übernommen aus MainframeMate {@code JwbfWikiContentService}: eigener {@link CookieManager} je Site
 * (keine globale {@code CookieHandler}-Einstellung), alle Cookies in genau einem {@code Cookie}-Header,
 * Redirects manuell, damit Session-Cookies über Weiterleitungen erhalten bleiben. Anders als dort werden
 * weder Cookies, Tokens noch Antwortkörper geloggt. Die Proxy-Route liefert je Ziel der {@link HttpRoutePort}
 * der Composition Root und wird jeder Verbindung ausdrücklich mitgegeben (kein prozessweiter
 * {@code ProxySelector}); ohne Port gilt der JVM-Standard. Eine {@link SSLSocketFactory} (Vertrauensquellen
 * der Anwendung) wird je HTTPS-Verbindung gesetzt, nie global.
 */
final class UrlConnectionMediaWikiTransport implements MediaWikiTransport {

    private static final int MAX_REDIRECTS = 5;

    private final MediaWikiSiteConfig site;
    private final String endpoint;
    private final HttpRoutePort routes;
    private final SSLSocketFactory sslSocketFactory;
    private volatile CookieManager cookies = newCookieManager();

    UrlConnectionMediaWikiTransport(MediaWikiSiteConfig site) {
        this(site, null, null);
    }

    UrlConnectionMediaWikiTransport(MediaWikiSiteConfig site, HttpRoutePort routes, SSLSocketFactory sslSocketFactory) {
        this.site = site;
        this.endpoint = site.apiEndpoint();
        this.routes = routes;
        this.sslSocketFactory = sslSocketFactory;
    }

    @Override
    public Response get(String query) throws IOException {
        return execute(endpoint + "?" + query, null);
    }

    @Override
    public Response postForm(String formBody) throws IOException {
        return execute(endpoint, formBody);
    }

    @Override
    public byte[] getImage(URI target, int maxBytes) throws IOException {
        URI origin = URI.create(endpoint);
        URI current = target;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (current.getRawUserInfo() != null || !sameOrigin(origin, current)) {
                throw new IOException("Image URL outside wiki origin");
            }
            HttpURLConnection conn = open(current.toString(), "GET");
            conn.setRequestProperty("Accept", "image/*");
            try {
                int status = conn.getResponseCode();
                storeCookies(conn);
                String location = conn.getHeaderField("Location");
                if (isRedirect(status) && location != null && !location.isEmpty()) {
                    current = current.resolve(location);
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new MediaWikiImageAccessException(status);
                }
                if (status != 200) {
                    throw new IOException("Image request failed (HTTP " + status + ")");
                }
                String type = conn.getContentType();
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")
                        || type.toLowerCase(java.util.Locale.ROOT).startsWith("image/svg")) {
                    throw new IOException("Unsupported image content type");
                }
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (count > maxBytes - out.size()) {
                            throw new IOException("Image exceeds size limit");
                        }
                        out.write(buffer, 0, count);
                    }
                    return out.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many image redirects");
    }

    @Override
    public void resetSession() {
        cookies = newCookieManager();
    }

    private Response execute(String url, String formBody) throws IOException {
        String currentUrl = url;
        String body = formBody;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = open(currentUrl, body == null ? "GET" : "POST");
            try {
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    OutputStream out = conn.getOutputStream();
                    try {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                }
                int status = conn.getResponseCode();
                storeCookies(conn);
                String location = conn.getHeaderField("Location");
                if (isRedirect(status) && location != null && !location.isEmpty()) {
                    String target = resolve(currentUrl, location);
                    if (status == HttpURLConnection.HTTP_SEE_OTHER) {
                        body = null;
                    }
                    checkRedirect(currentUrl, target, body != null);
                    currentUrl = target;
                    drain(conn, status);
                    continue;
                }
                return new Response(status, read(status >= 400 ? conn.getErrorStream() : conn.getInputStream()));
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("too many redirects from " + site.displayName());
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = RouteConnections.open(toUri(url), routes, sslSocketFactory, site.userAgent());
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setConnectTimeout(site.connectTimeoutMillis());
        conn.setReadTimeout(site.readTimeoutMillis());
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Charset", "UTF-8");
        String cookieHeader = cookieHeader(url);
        if (!cookieHeader.isEmpty()) {
            conn.setRequestProperty("Cookie", cookieHeader);
        }
        return conn;
    }

    private String cookieHeader(String url) throws IOException {
        Map<String, List<String>> headers =
                cookies.get(toUri(url), Collections.<String, List<String>>emptyMap());
        StringBuilder merged = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (merged.length() > 0) {
                    merged.append("; ");
                }
                merged.append(value);
            }
        }
        return merged.toString();
    }

    private void storeCookies(HttpURLConnection conn) throws IOException {
        cookies.put(toUri(conn.getURL().toString()), conn.getHeaderFields());
    }

    private static URI toUri(String url) throws IOException {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("invalid URL", e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308;
    }

    /**
     * Keine Weiterleitung von HTTPS auf HTTP; Formulardaten (Login mit Passwort) nur an denselben Origin.
     */
    static void checkRedirect(String current, String target, boolean carriesForm) throws IOException {
        URI from = toUri(current);
        URI to = toUri(target);
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(to.getScheme())) {
            throw new IOException("redirect from HTTPS to " + to.getScheme() + " refused");
        }
        if (carriesForm && !sameOrigin(from, to)) {
            throw new IOException("redirect of form data to another origin refused");
        }
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String resolve(String current, String location) throws IOException {
        try {
            return new URL(new URL(current), location).toExternalForm();
        } catch (MalformedURLException e) {
            throw new IOException("invalid redirect location", e);
        }
    }

    private static void drain(HttpURLConnection conn, int status) {
        try {
            read(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } catch (IOException ignored) {
            // Redirect-Körper ist bedeutungslos.
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static CookieManager newCookieManager() {
        return new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    }
}

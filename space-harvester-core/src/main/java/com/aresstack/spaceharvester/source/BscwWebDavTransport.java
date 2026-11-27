package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import com.aresstack.spaceharvester.source.network.RoutedWebDavHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.X509TrustManager;

/** Read BSCW WebDAV through the same routed Reactor Netty stack as Confluence. */
final class BscwWebDavTransport {

    private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
    /** So vielen Weiterleitungen folgt eine Seite der Weboberfläche höchstens (die Suche braucht zwei). */
    static final int MAX_PAGE_REDIRECTS = 5;

    private final URI root;
    private final ConfiguredSecrets secrets;
    private final SecretRef secretRef;
    private final String userAgent;
    private final int maxFileBytes;
    private final RoutedWebDavHttp http;

    BscwWebDavTransport(URI root, String sourceId, ConfiguredSecrets secrets, HttpRoutePort routes,
                        ProxyCredentials proxyCredentials, String userAgent, int maxFileBytes,
                        X509TrustManager trustManager) {
        this(root, sourceId, secrets, routes, proxyCredentials, userAgent, maxFileBytes, trustManager,
                com.aresstack.spaceharvester.source.network.SourceTimeouts.DEFAULT_WEBDAV_REQUEST);
    }

    BscwWebDavTransport(URI root, String sourceId, ConfiguredSecrets secrets, HttpRoutePort routes,
                        ProxyCredentials proxyCredentials, String userAgent, int maxFileBytes,
                        X509TrustManager trustManager, java.time.Duration requestTimeout) {
        this.root = root;
        this.secrets = secrets;
        this.secretRef = ConfiguredSecrets.refFor(sourceId);
        this.userAgent = userAgent;
        this.maxFileBytes = maxFileBytes;
        this.http = new RoutedWebDavHttp(routes, proxyCredentials, trustManager,
                Math.max(MAX_METADATA_BYTES, maxFileBytes), requestTimeout);
    }

    byte[] properties(URI resource, int depth) throws KnowledgeSourceException {
        return request(resource, "PROPFIND", depth, MAX_METADATA_BYTES, 207);
    }

    /** Read a bounded part of an original file; never follow authentication redirects. */
    byte[] downloadRange(URI resource, long offset, int count) throws KnowledgeSourceException {
        if (offset < 0 || count < 1 || count > 262144 || offset > Long.MAX_VALUE - count) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Invalid BSCW byte range");
        }
        String range = "bytes=" + offset + "-" + (offset + count - 1);
        RoutedWebDavHttp.Result response = exchange(resource, "GET", -1, count, range);
        if (response.status() != 200 && response.status() != 206) {
            throw status(response.status());
        }
        if (response.status() != 206) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED,
                    "BSCW unterstützt für dieses Video keine HTTP-Range-Abfragen (HTTP "
                            + response.status() + ")");
        }
        return response.body();
    }

    /**
     * Read the part of an original file from {@code offset}, at most {@code count} bytes (one chunk of a download to
     * disk); the last chunk may be shorter. If BSCW ignores the range on the first chunk, its answer is the whole file,
     * within the usual file allowance ({@link Chunk#whole()}). Never follow authentication redirects.
     *
     * @throws KnowledgeSourceException {@code UNSUPPORTED} if BSCW ignores the range after the first chunk
     */
    Chunk downloadChunk(URI resource, long offset, int count) throws KnowledgeSourceException {
        if (offset < 0 || count < 1 || count > maxFileBytes || offset > Long.MAX_VALUE - count) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Invalid BSCW byte range");
        }
        RoutedWebDavHttp.Result response = exchange(resource, "GET", -1, offset == 0 ? maxFileBytes : count,
                "bytes=" + offset + "-" + (offset + count - 1));
        if (response.status() == 416) {
            return new Chunk(new byte[0], false);
        }
        if (response.status() == 200 && offset == 0) {
            return new Chunk(response.body(), true);
        }
        if (response.status() != 200 && response.status() != 206) {
            // Ein Fehlerstatus (etwa 503 oder 401) ist ein Fehler des Abrufs, nicht „keine Range“; 5xx und 429 sind
            // vorübergehend und werden als Stück neu angefordert.
            throw status(response.status());
        }
        if (response.status() != 206 || response.body().length > count) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED,
                    "BSCW unterstützt für diese Datei keine HTTP-Range-Abfragen (HTTP " + response.status() + ")");
        }
        return new Chunk(response.body(), false);
    }

    /** One answer of {@link #downloadChunk}: a part, or with {@code whole} the complete file. */
    record Chunk(byte[] bytes, boolean whole) {
    }

    byte[] download(URI resource) throws KnowledgeSourceException {
        return downloadResource(resource).bytes();
    }

    /**
     * Treat a WebDAV redirect as a link resource, not as a failed file download.
     * Never follow a redirect with source credentials.
     */
    Download downloadResource(URI resource) throws KnowledgeSourceException {
        RoutedWebDavHttp.Result response = exchange(resource, "GET", -1, maxFileBytes);
        if (response.status() == 200) {
            return new Download(response.body(), null);
        }
        if (response.status() == 301 || response.status() == 302 || response.status() == 303
                || response.status() == 307 || response.status() == 308) {
            URI destination = redirectTarget(checked(resource), response.location());
            return new Download(destination.toASCIIString().getBytes(StandardCharsets.UTF_8), destination);
        }
        throw status(response.status());
    }

    record Download(byte[] bytes, URI linkedTarget) {
        boolean isLink() {
            return linkedTarget != null;
        }
    }

    private static URI redirectTarget(URI source, String location) throws KnowledgeSourceException {
        try {
            if (location == null || location.isBlank() || location.length() > 8192) {
                throw new IllegalArgumentException("Missing or oversized Location header");
            }
            URI target = source.resolve(URI.create(location)).normalize();
            String scheme = target.getScheme();
            if (target.getHost() == null || target.getRawUserInfo() != null
                    || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Redirect target must be an HTTP(S) URL without credentials");
            }
            return target;
        } catch (IllegalArgumentException invalid) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW WebDAV redirect has no safe target");
        }
    }

    /** Reject off-origin links and unexpected DAV paths before using credentials. */
    URI checked(URI resource) throws KnowledgeSourceException {
        if (resource == null || !root.getScheme().equalsIgnoreCase(resource.getScheme())
                || !root.getHost().equalsIgnoreCase(resource.getHost())
                || root.getPort() != resource.getPort() || resource.getRawUserInfo() != null
                || resource.getRawQuery() != null || resource.getRawFragment() != null
                || !resource.normalize().getRawPath().startsWith("/sec/bscw.cgi/")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "WebDAV resource is outside the BSCW server");
        }
        return resource.normalize();
    }

    URI resolve(String href) throws KnowledgeSourceException {
        try {
            return checked(root.resolve(href));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Invalid WebDAV href");
        }
    }

    private byte[] request(URI resource, String method, int depth, int limit, int expected)
            throws KnowledgeSourceException {
        RoutedWebDavHttp.Result response = exchange(resource, method, depth, limit);
        if (response.status() != expected) {
            throw status(response.status());
        }
        return response.body();
    }

    private RoutedWebDavHttp.Result exchange(URI resource, String method, int depth, int limit)
            throws KnowledgeSourceException {
        return exchange(resource, method, depth, limit, null);
    }

    private RoutedWebDavHttp.Result exchange(URI resource, String method, int depth, int limit, String range)
            throws KnowledgeSourceException {
        Map<String, String> extra = new LinkedHashMap<>();
        if (depth >= 0) {
            extra.put("Depth", Integer.toString(depth));
        }
        if (range != null) {
            extra.put("Range", range);
        }
        return send(checked(resource), method, extra, null, limit, range != null);
    }

    /**
     * Eine Seite der Weboberfläche (Ordner oder Suchergebnis) als HTML, mit den Zugangsdaten der Quelle. Weiterleitungen
     * folgt der Abruf nur auf demselben Server unter {@code /sec/bscw.cgi} (siehe {@link #checkedPage}).
     */
    byte[] page(URI page) throws KnowledgeSourceException {
        return follow(checkedPage(page), send(checkedPage(page), "GET", html(), null, MAX_METADATA_BYTES, false));
    }

    /**
     * Schickt ein Formular der Weboberfläche ({@code application/x-www-form-urlencoded}) an {@code target} und liefert
     * die Seite, auf die BSCW danach weiterleitet (bei der Suche der Suchordner mit den Treffern).
     */
    byte[] submit(URI target, String form) throws KnowledgeSourceException {
        URI checked = checkedPage(target);
        Map<String, String> headers = html();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        return follow(checked, send(checked, "POST", headers, form.getBytes(StandardCharsets.UTF_8),
                MAX_METADATA_BYTES, false));
    }

    private byte[] follow(URI current, RoutedWebDavHttp.Result response) throws KnowledgeSourceException {
        for (int hop = 0; ; hop++) {
            if (response.status() == 200) {
                return response.body();
            }
            boolean redirect = response.status() == 301 || response.status() == 302 || response.status() == 303
                    || response.status() == 307 || response.status() == 308;
            if (!redirect || hop >= MAX_PAGE_REDIRECTS) {
                throw status(response.status());
            }
            current = checkedPage(redirectTarget(current, response.location()));
            response = send(current, "GET", html(), null, MAX_METADATA_BYTES, false);
        }
    }

    private static Map<String, String> html() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/html");
        return headers;
    }

    /**
     * Wie {@link #checked}, aber für Seiten der Weboberfläche: Eine Abfrage ({@code ?op=…}) ist erlaubt, der Pfad muss
     * {@code /sec/bscw.cgi} sein oder darunter liegen.
     */
    URI checkedPage(URI page) throws KnowledgeSourceException {
        if (page == null || !root.getScheme().equalsIgnoreCase(page.getScheme())
                || !root.getHost().equalsIgnoreCase(page.getHost())
                || root.getPort() != page.getPort() || page.getRawUserInfo() != null
                || page.getRawFragment() != null || page.normalize().getRawPath() == null
                || !(page.normalize().getRawPath().startsWith("/sec/bscw.cgi/")
                || page.normalize().getRawPath().equals("/sec/bscw.cgi"))) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Page is outside the BSCW server");
        }
        return page.normalize();
    }

    private RoutedWebDavHttp.Result send(URI target, String method, Map<String, String> extra, byte[] body, int limit,
                                         boolean limited) throws KnowledgeSourceException {
        try (SecretMaterial secret = secrets.resolve(secretRef)) {
            if (!secret.hasPrincipal()) {
                throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BSCW username missing");
            }
            char[] password = secret.copySecret();
            Map<String, String> headers = new LinkedHashMap<>();
            try {
                headers.put("Authorization", basic(secret.principal(), password));
            } finally {
                Arrays.fill(password, '\0');
            }
            if (userAgent != null) {
                headers.put("User-Agent", userAgent);
            }
            headers.putAll(extra);
            RoutedWebDavHttp.Result response = body != null ? http.exchange(target, method, headers, body, limit)
                    : limited ? http.exchange(target, method, headers, limit)
                    : http.exchange(target, method, headers);
            if (response.body().length > limit) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW WebDAV response too large");
            }
            return response;
        } catch (SecretUnavailableException e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BSCW credentials unavailable");
        } catch (RoutedWebDavHttp.RouteUnavailableException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "BSCW WebDAV route-unavailable: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "BSCW WebDAV request failed (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static String basic(String username, char[] password) {
        byte[] bytes = (username + ":" + new String(password)).getBytes(StandardCharsets.UTF_8);
        try {
            return "Basic " + Base64.getEncoder().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static KnowledgeSourceException status(int status) {
        Kind kind = status == 401 || status == 403 ? Kind.ACCESS_DENIED
                : status == 407 || status == 429 || status >= 500 ? Kind.UNAVAILABLE
                : status == 404 ? Kind.NOT_FOUND : Kind.INVALID_RESPONSE;
        return new KnowledgeSourceException(kind, "BSCW WebDAV HTTP " + status);
    }
}

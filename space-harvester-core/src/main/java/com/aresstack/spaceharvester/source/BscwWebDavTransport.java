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

    private final URI root;
    private final ConfiguredSecrets secrets;
    private final SecretRef secretRef;
    private final String userAgent;
    private final int maxFileBytes;
    private final RoutedWebDavHttp http;

    BscwWebDavTransport(URI root, String sourceId, ConfiguredSecrets secrets, HttpRoutePort routes,
                        ProxyCredentials proxyCredentials, String userAgent, int maxFileBytes,
                        X509TrustManager trustManager) {
        this.root = root;
        this.secrets = secrets;
        this.secretRef = ConfiguredSecrets.refFor(sourceId);
        this.userAgent = userAgent;
        this.maxFileBytes = maxFileBytes;
        this.http = new RoutedWebDavHttp(routes, proxyCredentials, trustManager,
                Math.max(MAX_METADATA_BYTES, maxFileBytes));
    }

    byte[] properties(URI resource, int depth) throws KnowledgeSourceException {
        return request(resource, "PROPFIND", depth, MAX_METADATA_BYTES, 207);
    }

    byte[] download(URI resource) throws KnowledgeSourceException {
        return request(resource, "GET", -1, maxFileBytes, 200);
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
        URI target = checked(resource);
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
            if (depth >= 0) {
                headers.put("Depth", Integer.toString(depth));
            }
            RoutedWebDavHttp.Result response = http.exchange(target, method, headers);
            if (response.status() != expected) {
                throw status(response.status());
            }
            if (response.body().length > limit) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW WebDAV response too large");
            }
            return response.body();
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

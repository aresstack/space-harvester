package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Read a BSCW WebDAV endpoint without mounting a Windows drive. */
final class BscwWebDavTransport {

    private static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;

    private final URI root;
    private final ConfiguredSecrets secrets;
    private final SecretRef secretRef;
    private final HttpRoutePort routes;
    private final ProxyCredentials proxyCredentials;
    private final String userAgent;
    private final int maxFileBytes;
    private final SSLContext sslContext;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    BscwWebDavTransport(URI root, String sourceId, ConfiguredSecrets secrets, HttpRoutePort routes,
                        ProxyCredentials proxyCredentials, String userAgent, int maxFileBytes,
                        X509TrustManager trustManager) {
        this.root = root;
        this.secrets = secrets;
        this.secretRef = ConfiguredSecrets.refFor(sourceId);
        this.routes = routes;
        this.proxyCredentials = proxyCredentials;
        this.userAgent = userAgent;
        this.maxFileBytes = maxFileBytes;
        this.sslContext = sslContext(trustManager);
    }

    private static SSLContext sslContext(X509TrustManager trustManager) {
        if (trustManager == null) {
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustManager}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialize BSCW TLS context", e);
        }
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
        HttpRoute route = routes.routeFor(target);
        if (route.isUnavailable()) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "BSCW WebDAV route-unavailable: " + route.describe());
        }
        HttpClient client = clientFor(route);

        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (depth >= 0) {
            builder.header("Depth", Integer.toString(depth));
        }
        try (SecretMaterial secret = secrets.resolve(secretRef)) {
            if (!secret.hasPrincipal()) {
                throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BSCW username missing");
            }
            char[] password = secret.copySecret();
            try {
                builder.header("Authorization", basic(secret.principal(), password));
            } finally {
                Arrays.fill(password, '\0');
            }
            HttpResponse<InputStream> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != expected) {
                    throw status(response.statusCode());
                }
                return readLimited(body, limit);
            }
        } catch (SecretUnavailableException e) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "BSCW credentials unavailable");
        } catch (IOException e) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE,
                    "BSCW WebDAV connection failed (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "BSCW WebDAV request interrupted");
        }
    }

    private HttpClient clientFor(HttpRoute route) {
        String key = route.isProxy() ? route.proxyHost() + ":" + route.proxyPort() : "DIRECT";
        return clients.computeIfAbsent(key, ignored -> {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1);
            if (sslContext != null) {
                builder.sslContext(sslContext);
            }
            if (route.isProxy()) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(route.proxyHost(), route.proxyPort())));
                builder.authenticator(new ProxyOnlyAuthenticator(proxyCredentials));
            }
            return builder.build();
        });
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
                : status == 407 ? Kind.UNAVAILABLE
                : status == 404 ? Kind.NOT_FOUND
                : status == 429 || status >= 500 ? Kind.UNAVAILABLE : Kind.INVALID_RESPONSE;
        return new KnowledgeSourceException(kind, "BSCW WebDAV HTTP " + status);
    }

    private static byte[] readLimited(InputStream stream, int limit) throws IOException, KnowledgeSourceException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            if (result.size() > limit - length) {
                throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "BSCW WebDAV response too large");
            }
            result.write(buffer, 0, length);
        }
        return result.toByteArray();
    }

    private static final class ProxyOnlyAuthenticator extends Authenticator {

        private final ProxyCredentials credentials;

        private ProxyOnlyAuthenticator(ProxyCredentials credentials) {
            this.credentials = credentials;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) {
                return null;
            }
            return credentials.get().orElse(null);
        }
    }
}

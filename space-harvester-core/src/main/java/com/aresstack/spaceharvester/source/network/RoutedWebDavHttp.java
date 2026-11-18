package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import io.netty.handler.codec.http.HttpMethod;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * Execute WebDAV HTTP requests through the same route, proxy authentication and TLS strategy
 * as the Confluence transport, without introducing a Spring dependency outside its designated client.
 * Do not use the JVM's implicit proxy selection.
 */
public final class RoutedWebDavHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final HttpRoutePort routes;
    private final ProxyCredentials proxyCredentials;
    private final JdkSslContext sslContext;
    private final int maximumResponseBytes;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public RoutedWebDavHttp(HttpRoutePort routes, ProxyCredentials proxyCredentials,
                            X509TrustManager trustManager, int maximumResponseBytes) {
        this.routes = routes;
        this.proxyCredentials = proxyCredentials == null ? ProxyCredentials.none() : proxyCredentials;
        this.sslContext = tls(trustManager);
        if (maximumResponseBytes < 1) {
            throw new IllegalArgumentException("maximumResponseBytes must be positive");
        }
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public Result exchange(URI uri, String method, Map<String, String> headers) {
        HttpRoute route = routes.routeFor(uri);
        if (route == null || route.isUnavailable()) {
            throw new RouteUnavailableException(route == null ? "no-route" : route.describe());
        }
        HttpClient client = clients.computeIfAbsent(routeKey(route, uri),
                ignored -> clientFor(route, uri));
        Result response = client.headers(httpHeaders -> headers.forEach(httpHeaders::set))
                .request(HttpMethod.valueOf(method))
                .uri(uri)
                .response((received, content) -> content.asByteArray()
                        .reduce(new ByteArrayOutputStream(), (buffer, bytes) -> {
                            if (bytes.length > maximumResponseBytes - buffer.size()) {
                                throw new IllegalStateException("WebDAV response exceeds maximum size");
                            }
                            buffer.write(bytes, 0, bytes.length);
                            return buffer;
                        })
                        .map(buffer -> new Result(received.status().code(), buffer.toByteArray())))
                .next().block(READ_TIMEOUT.plusSeconds(5));
        if (response == null) {
            throw new IllegalStateException("WebDAV returned no response");
        }
        return response;
    }

    private HttpClient clientFor(HttpRoute route, URI target) {
        HttpClient http = HttpClient.create()
                .resolver(route.isProxy() ? NoopAddressResolverGroup.INSTANCE
                        : DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(READ_TIMEOUT)
                .compress(false)
                .followRedirect(false);
        if (route.isProxy()) {
            PasswordAuthentication login = proxyCredentials.get().orElse(null);
            String username = login == null ? null : login.getUserName();
            String password = login == null ? null : new String(login.getPassword());
            if (login != null) {
                java.util.Arrays.fill(login.getPassword(), '\0');
            }
            http = http.proxy(proxy -> {
                ProxyProvider.Builder builder = proxy.type(ProxyProvider.Proxy.HTTP)
                        .socketAddress(InetSocketAddress.createUnresolved(route.proxyHost(), route.proxyPort()))
                        .connectTimeoutMillis(CONNECT_TIMEOUT.toMillis());
                if (username != null) {
                    builder.username(username).password(ignored -> password);
                }
            });
        } else {
            http = http.noProxy();
        }
        if ("https".equalsIgnoreCase(target.getScheme())) {
            http = http.secure(tls -> tls.sslContext(sslContext));
        }
        return http;
    }

    private static String routeKey(HttpRoute route, URI uri) {
        return (route.isProxy() ? "PROXY " + route.proxyHost() + ":" + route.proxyPort() : "DIRECT")
                + "|" + uri.getScheme() + "://" + uri.getAuthority();
    }

    private static JdkSslContext tls(X509TrustManager trustManager) {
        try {
            SSLContext context;
            if (trustManager == null) {
                context = SSLContext.getDefault();
            } else {
                context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {trustManager}, null);
            }
            return new JdkSslContext(context, true, null, IdentityCipherSuiteFilter.INSTANCE,
                    ApplicationProtocolConfig.DISABLED, ClientAuth.NONE, null, false);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialize WebDAV TLS context", exception);
        }
    }

    public record Result(int status, byte[] body) {
    }

    public static final class RouteUnavailableException extends RuntimeException {
        public RouteUnavailableException(String message) {
            super(message);
        }
    }
}

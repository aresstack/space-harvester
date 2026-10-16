package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceAsyncHttpTransport;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceSourceProvider;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * Asynchrone Transporte für native Seiten-Snapshots (#47): je Quelle ein {@link WebClientConfluenceTransport} mit
 * derselben Proxy-Route, demselben Server-Vertrauen und demselben Client-Zertifikat wie der synchrone Transport, dazu
 * ein gemeinsamer Executor für die JSON-Abbildung, damit sie nie auf einem Netty-Event-Loop läuft. Besitzt alle
 * gebauten Transporte und gibt sie mit {@link #close()} frei.
 */
public final class AsyncSnapshotTransports implements AutoCloseable {

    private final SecretProvider secrets;
    private final HttpRoutePort routes;
    private final String userAgent;
    private final List<WebClientConfluenceTransport> transports = new CopyOnWriteArrayList<>();
    private final ExecutorService mapping;

    /** @param threads Threads der JSON-Abbildung, mindestens 1 */
    public AsyncSnapshotTransports(SecretProvider secrets, HttpRoutePort routes, String userAgent, int threads) {
        if (secrets == null || routes == null) {
            throw new IllegalArgumentException("secrets and routes must not be null");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        this.secrets = secrets;
        this.routes = routes;
        this.userAgent = userAgent;
        AtomicInteger counter = new AtomicInteger();
        this.mapping = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "confluence-snapshot-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Standard: so viele Abbildungs-Threads wie Prozessoren, mindestens zwei. */
    public AsyncSnapshotTransports(SecretProvider secrets, HttpRoutePort routes, String userAgent) {
        this(secrets, routes, userAgent, Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    /** Derselbe Provider mit asynchronen Snapshots über Transporte dieser Instanz. */
    ConfluenceSourceProvider attach(ConfluenceSourceProvider provider, X509TrustManager trust, String thumbprint) {
        return provider.withAsyncTransports(new PerSource(trust, thumbprint), mapping);
    }

    /** Anzahl gebauter Transporte (für Tests und Diagnose). */
    public int transports() {
        return transports.size();
    }

    @Override
    public void close() {
        transports.forEach(WebClientConfluenceTransport::close);
        transports.clear();
        mapping.shutdownNow();
    }

    /** Baut den Transport einer Quelle mit deren Vertrauen und Fingerabdruck. */
    private final class PerSource implements ConfluenceSourceProvider.AsyncTransports {
        private final X509TrustManager trust;
        private final String thumbprint;

        PerSource(X509TrustManager trust, String thumbprint) {
            this.trust = trust;
            this.thumbprint = thumbprint;
        }

        @Override
        public ConfluenceAsyncHttpTransport transport(SourceAddress sourceId, String alias, Path keyStoreFile,
                                                      SecretRef passwordRef, int connectTimeoutMillis,
                                                      int readTimeoutMillis) {
            SSLContext trustOnly = ConfluenceSources.trustOnlyContext(trust);
            // Dieselben Bausteine wie beim synchronen Transport (#33): unbrauchbare Zertifikate scheitern hier sofort.
            SSLContext clientTls = alias == null ? trustOnly
                    : new ConfiguredClientCertificates(secrets, trust, thumbprint).sslContext(alias, keyStoreFile,
                            passwordRef);
            WebClientConfluenceTransport transport = WebClientConfluenceTransport.builder()
                    .routes(routes)
                    .clientTls(clientTls)
                    .anonymousTls(trustOnly)
                    .userAgent(userAgent)
                    .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                    .readTimeout(Duration.ofMillis(readTimeoutMillis))
                    .build();
            transports.add(transport);
            return transport;
        }
    }
}

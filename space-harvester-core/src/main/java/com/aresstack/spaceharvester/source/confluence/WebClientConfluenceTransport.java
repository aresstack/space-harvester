package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceAsyncHttpTransport;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceRedirects;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceResponseTooLargeException;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.application.jobs.RequestRoute;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

/**
 * Asynchroner Confluence-Transport mit Spring {@link WebClient} auf Reactor Netty (#47, ADR 0001): echte
 * nicht blockierende I/O hinter {@link ConfluenceAsyncHttpTransport}, mit derselben Bedeutung wie der synchrone
 * {@code UrlConnectionConfluenceTransport}.
 *
 * <ul>
 *     <li>Route: {@link HttpRoutePort#routeFor(URI)} je Ziel-URL und je Weiterleitungsschritt, auf einem begrenzten
 *     eigenen Pool, nie auf einem Netty-Event-Loop (PAC-Abruf, PowerShell und JavaScript dürfen dauern). Eine nicht
 *     verfügbare Route beendet die Anfrage mit einer {@link IOException}, nie direkt.</li>
 *     <li>Je effektiver Route, Ziel (Schema, Host, Port) und TLS-Identität (mit oder ohne Client-Zertifikat) ein
 *     eigener Client mit eigenem Verbindungspool; Tunnel und Zertifikate werden nie zwischen ihnen geteilt.</li>
 *     <li>Begrenzte Parallelität: höchstens {@code maxConnections} Verbindungen und {@code maxPending} wartende
 *     Anfragen je Pool; darüber endet die Anfrage sofort mit einer {@link IOException}.</li>
 *     <li>Körper byte-genau, ohne Kompression angefordert; die Obergrenze gilt beim Empfang, auch chunked.</li>
 *     <li>Weiterleitungen und Cookies nach {@link ConfluenceRedirects}, wie der synchrone Transport.</li>
 *     <li>Namensauflösung über die JVM (wie {@code HttpURLConnection}, inklusive Hosts-Datei und Windows-DNS).</li>
 * </ul>
 *
 * <p>Header werden nie geloggt. {@link #close()} gibt alle Pools und den Routen-Pool frei.
 */
public final class WebClientConfluenceTransport implements ConfluenceAsyncHttpTransport, AutoCloseable {

    /** Name dieses Transports in Diagnoseereignissen ({@link RequestRoute}). */
    static final String TRANSPORT = "WebClient";

    /** Obergrenze gleichzeitig gehaltener Clients (Route × Ziel × TLS); der älteste wird freigegeben. */
    static final int MAX_CLIENTS = 64;

    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private final HttpRoutePort routes;
    private final SslContext clientTls;
    private final SslContext anonymousTls;
    private final String userAgent;
    private final ProxyCredentials proxyCredentials;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxConnections;
    private final int maxPending;
    private final String name;
    private final Scheduler routeScheduler;
    private final Map<ClientKey, Client> clients = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Hart geschlossen ({@link #close()}): auch schon begonnene Anfragen bekommen keinen Client mehr. */
    private volatile boolean disposed;

    private WebClientConfluenceTransport(Builder builder) {
        this.routes = builder.routes == null ? HttpRoutePort.direct() : builder.routes;
        this.clientTls = netty(builder.clientTls);
        this.anonymousTls = netty(builder.anonymousTls);
        this.userAgent = builder.userAgent;
        this.proxyCredentials = builder.proxyCredentials;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.maxConnections = builder.maxConnections;
        this.maxPending = builder.maxPending;
        this.name = "confluence-webclient-" + INSTANCES.incrementAndGet();
        this.routeScheduler = Schedulers.newBoundedElastic(builder.routeThreads, builder.routeQueue,
                name + "-route", 60, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionStage<ConfluenceHttpResponse> getAsync(URI uri, Map<String, String> headers, int maxBytes) {
        return request(uri, headers, maxBytes, Identity.CLIENT);
    }

    /** Ohne Client-Zertifikat: nur die Vertrauensquellen ({@link Builder#anonymousTls(SSLContext)}). */
    @Override
    public CompletionStage<ConfluenceHttpResponse> getAnonymousAsync(URI uri, Map<String, String> headers,
                                                                     int maxBytes) {
        return request(uri, headers, maxBytes, Identity.ANONYMOUS);
    }

    private CompletionStage<ConfluenceHttpResponse> request(URI uri, Map<String, String> headers, int maxBytes,
                                                            Identity identity) {
        return Mono.defer(() -> closed.get()
                        ? Mono.<ConfluenceHttpResponse>error(new IOException("Transport ist geschlossen"))
                        : exchange(uri, headers, maxBytes, identity, new LinkedHashMap<>(), new ArrayList<>(), 0))
                .onErrorMap(error -> !(error instanceof IOException), WebClientConfluenceTransport::ioFailure)
                .toFuture();
    }

    /**
     * Ein einzelner GET mit Client-Zertifikat, ohne Weiterleitungen zu verfolgen (3xx kommt als Antwort) und ohne
     * Cookies weiterzutragen, mit einer Zeitgrenze für alles einschließlich Routenauswahl. Bei Ablauf wird die
     * Anfrage abgebrochen und endet mit {@link SocketTimeoutException}. Für Aufrufe mit anfragebezogener
     * Anmeldung ({@link DelegatedConfluenceHttp}); die Header bestimmt allein der Aufrufer.
     */
    CompletionStage<ConfluenceHttpResponse> getOnceAsync(URI uri, Map<String, String> headers, int maxBytes,
                                                         Duration timeout) {
        return Mono.defer(() -> closed.get()
                        ? Mono.<ConfluenceHttpResponse>error(new IOException("Transport ist geschlossen"))
                        : once(uri, headers, maxBytes, Identity.CLIENT))
                .map(response -> new ConfluenceHttpResponse(response.status(), response.contentType(),
                        response.body(), response.location(), response.cookies(), uri, List.of()))
                .timeout(timeout, Mono.defer(() -> Mono.error(new SocketTimeoutException(
                        "Zeitgrenze von " + timeout.toMillis() + " ms überschritten"))))
                .onErrorMap(error -> !(error instanceof IOException), WebClientConfluenceTransport::ioFailure)
                .toFuture();
    }

    /** Ein Request mit Weiterleitungen auf demselben Host, wie {@code UrlConnectionConfluenceTransport}. */
    private Mono<ConfluenceHttpResponse> exchange(URI current, Map<String, String> headers, int maxBytes,
                                                  Identity identity, Map<String, String> cookies,
                                                  List<String> redirects, int hop) {
        Map<String, String> sent = hop == 0 ? headers : ConfluenceRedirects.withCookies(headers, cookies);
        return once(current, sent, maxBytes, identity).flatMap(response -> {
            ConfluenceRedirects.merge(cookies, response.cookies());
            URI next = hop < ConfluenceRedirects.MAX_REDIRECTS
                    ? ConfluenceRedirects.sameHostRedirect(current, response.status(), response.location())
                    : null;
            if (next == null) {
                return Mono.just(new ConfluenceHttpResponse(response.status(), response.contentType(),
                        response.body(), response.location(), ConfluenceRedirects.pairs(cookies), current,
                        redirects));
            }
            redirects.add(next.toString());
            return exchange(next, headers, maxBytes, identity, cookies, redirects, hop + 1);
        });
    }

    private Mono<ConfluenceHttpResponse> once(URI uri, Map<String, String> headers, int maxBytes,
                                              Identity identity) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return Mono.error(new IOException("nur http/https unterstützt: " + uri.getScheme()));
        }
        if (uri.getHost() == null) {
            return Mono.error(new IOException("URI ohne Host"));
        }
        return Mono.fromCallable(() -> routes.routeFor(uri))
                .subscribeOn(routeScheduler)
                .flatMap(route -> send(uri, route, headers, maxBytes, identity));
    }

    private Mono<ConfluenceHttpResponse> send(URI uri, HttpRoute route, Map<String, String> headers, int maxBytes,
                                              Identity identity) {
        if (route == null) {
            return Mono.error(new IOException("keine Routenentscheidung für " + uri.getHost()));
        }
        if (!route.isDirect() && !route.isProxy()) {
            return Mono.error(RequestRoute.attach(new IOException("Proxy-Route für " + uri.getHost()
                    + " nicht verfügbar: " + route.describe()), route.describe(), TRANSPORT));
        }
        WebClient client;
        try {
            client = client(ClientKey.of(route, uri, identity));
        } catch (IOException e) {
            return Mono.error(e);
        }
        return client.get()
                .uri(uri)
                .headers(sent -> {
                    if (userAgent != null) {
                        sent.set("User-Agent", userAgent);
                    }
                    headers.forEach(sent::set);
                })
                .exchangeToMono(response -> read(response, maxBytes))
                .onErrorMap(error -> routed(error, route));
    }

    /**
     * Der Fehler als {@link IOException} mit der Route dieses Abrufs ({@link RequestRoute}). Gemeinsame Netty-Fehler
     * ohne Stacktrace werden dafür in eine eigene {@link IOException} gehüllt.
     */
    private static IOException routed(Throwable error, HttpRoute route) {
        IOException failure = error instanceof IOException io ? io : ioFailure(error);
        if (failure.getStackTrace().length == 0) {
            failure = new IOException(failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage(), failure);
        }
        return RequestRoute.attach(failure, route.describe(), TRANSPORT);
    }

    private static Mono<ConfluenceHttpResponse> read(ClientResponse response, int maxBytes) {
        long length = response.headers().contentLength().orElse(-1);
        if (length > maxBytes) {
            return Mono.error(new ConfluenceResponseTooLargeException(maxBytes));
        }
        int status = response.statusCode().value();
        String contentType = first(response.headers().header("Content-Type"));
        String location = first(response.headers().header("Location"));
        List<String> cookies = ConfluenceRedirects.setCookies(Map.of("Set-Cookie",
                response.headers().header("Set-Cookie")));
        // Nur ein erfolgreicher Körper wird nach der angekündigten Länge vorbelegt; Fehler und Weiterleitungen sind klein.
        Body body = new Body(response.statusCode().is2xxSuccessful() ? length : -1, maxBytes);
        return response.bodyToFlux(DataBuffer.class)
                .handle((DataBuffer buffer, SynchronousSink<Void> sink) -> {
                    try {
                        int readable = buffer.readableByteCount();
                        if (body.size + (long) readable > maxBytes) {
                            sink.error(new ConfluenceResponseTooLargeException(maxBytes));
                            return;
                        }
                        body.reserve(readable);
                        buffer.read(body.bytes, body.size, readable);
                        body.size += readable;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .then(Mono.fromCallable(() -> new ConfluenceHttpResponse(status, contentType, body.bytes(),
                        location, cookies)));
    }

    /**
     * Der Körper in einem Feld, das bei bekannter Länge gleich passend angelegt wird: Die Pakete gehen direkt hinein,
     * ohne Zwischenpuffer je Paket und ohne Schlusskopie, wenn die angekündigte Länge stimmt (bei 100 MiB sonst bis zu
     * dreimal so viel Heap).
     */
    static final class Body {

        private static final int INITIAL = 8192;

        private final int maxBytes;
        byte[] bytes;
        int size;

        Body(long length, int maxBytes) {
            this.maxBytes = maxBytes;
            this.bytes = new byte[length >= 0 ? (int) length : Math.min(INITIAL, maxBytes)];
        }

        void reserve(int readable) {
            if (size + readable > bytes.length) {
                long grown = Math.max((long) bytes.length * 2, (long) size + readable);
                bytes = Arrays.copyOf(bytes, (int) Math.min(grown, maxBytes));
            }
        }

        byte[] bytes() {
            return size == bytes.length ? bytes : Arrays.copyOf(bytes, size);
        }
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    /** Netty- und Reactor-Fehler als {@link IOException}, wie der synchrone Transport sie meldet. */
    static IOException ioFailure(Throwable error) {
        Throwable cause = error;
        while (cause instanceof WebClientException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException) {
            SocketTimeoutException timeout = new SocketTimeoutException("Lese-Timeout");
            timeout.initCause(cause);
            return timeout;
        }
        if (cause.getClass().getName().endsWith("PoolAcquirePendingLimitException")) {
            return new IOException("zu viele gleichzeitige Anfragen an Confluence", cause);
        }
        String message = cause.getMessage();
        return new IOException(cause.getClass().getSimpleName() + (message == null ? "" : ": " + message), cause);
    }

    private WebClient client(ClientKey key) throws IOException {
        Client evicted = null;
        Client client;
        synchronized (clients) {
            if (disposed) {
                throw new IOException("Transport ist geschlossen");
            }
            if (closed.get()) {
                // Sanft geschlossen, während die Anfrage ihre Route wählte: Sie bekommt einen eigenen Client, der
                // danach schließt; neue Anfragen weist schon der Einstieg ab.
                Client late = create(key);
                retire(late);
                return late.web();
            }
            client = clients.get(key);
            if (client == null) {
                client = create(key);
                clients.put(key, client);
                if (clients.size() > MAX_CLIENTS) {
                    Map.Entry<ClientKey, Client> eldest = clients.entrySet().iterator().next();
                    evicted = eldest.getValue();
                    clients.remove(eldest.getKey());
                }
            }
        }
        if (evicted != null) {
            retire(evicted);
        }
        return client.web();
    }

    private Client create(ClientKey key) {
        ConnectionProvider pool = ConnectionProvider.builder(name + "-" + key.label())
                .maxConnections(maxConnections)
                .pendingAcquireMaxCount(maxPending)
                .pendingAcquireTimeout(connectTimeout.plus(readTimeout))
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
        // Über einen Proxy bleibt der Zielname unaufgelöst: im Unternehmensnetz kennt oft nur der Proxy externe Namen,
        // eine lokale Auflösung endete dort in einer UnknownHostException, obwohl die PAC-Route stimmte.
        HttpClient http = HttpClient.create(pool)
                .resolver(key.kind() == HttpRoute.Kind.PROXY ? NoopAddressResolverGroup.INSTANCE
                        : DefaultAddressResolverGroup.INSTANCE)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(readTimeout)
                .followRedirect(false)
                .compress(false);
        if (key.kind() == HttpRoute.Kind.PROXY) {
            // Die Anmeldung wird beim Bau des Clients gelesen (je Proxy und Ziel einmal) und von Netty bei jedem
            // Verbindungsaufbau mitgeschickt, auch im CONNECT-Tunnel; nie protokolliert.
            PasswordAuthentication login = proxyCredentials.get().orElse(null);
            String user = login == null ? null : login.getUserName();
            String password = login == null ? null : new String(login.getPassword());
            if (login != null) {
                java.util.Arrays.fill(login.getPassword(), '\0');
            }
            http = http.proxy(proxy -> {
                ProxyProvider.Builder spec = proxy.type(ProxyProvider.Proxy.HTTP)
                        .socketAddress(InetSocketAddress.createUnresolved(key.proxyHost(), key.proxyPort()))
                        .connectTimeoutMillis(connectTimeout.toMillis());
                if (user != null) {
                    spec.username(user).password(name -> password);
                }
            });
        } else {
            http = http.noProxy();
        }
        if ("https".equals(key.scheme())) {
            SslContext tls = key.identity() == Identity.CLIENT ? clientTls : anonymousTls;
            http = http.secure(spec -> spec.sslContext(tls));
        }
        WebClient web = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
        return new Client(web, pool);
    }

    private static SslContext netty(SSLContext context) {
        SSLContext jdk = context;
        if (jdk == null) {
            try {
                jdk = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("kein TLS-Standardkontext der JVM", e);
            }
        }
        return new JdkSslContext(jdk, true, null, IdentityCipherSuiteFilter.INSTANCE,
                ApplicationProtocolConfig.DISABLED, ClientAuth.NONE, null, false);
    }

    /**
     * Verwirft alle gehaltenen Clients, etwa nach einer geänderten Proxy-Anmeldung (ADR 0015): Die nächste Anfrage baut
     * neue Clients mit der dann gültigen Anmeldung. Die alten Pools schließen über {@code disposeLater}; laufende
     * Anfragen enden normal über ihre Verbindung, auch solche, die noch auf eine Verbindung warten ({@link #retire}).
     */
    public void discardClients() {
        List<Client> all;
        synchronized (clients) {
            all = new ArrayList<>(clients.values());
            clients.clear();
        }
        all.forEach(this::retire);
    }

    /**
     * Schließt den Pool eines nicht mehr gehaltenen Clients erst, wenn keine Anfrage mehr auf eine seiner Verbindungen
     * warten kann (nach {@code pendingAcquireTimeout}): {@code disposeLater} beendet wartende Anfragen mit einem
     * Fehler, laufende Verbindungen dagegen schließen erst nach ihrer Antwort.
     */
    private void retire(Client client) {
        Mono.delay(connectTimeout.plus(readTimeout))
                .then(Mono.defer(() -> client.pool().disposeLater()))
                .subscribe(ignored -> { }, failure -> { });
    }

    /** Anzahl gerade gehaltener Clients (für Tests der Pool-Trennung). */
    int clientCount() {
        synchronized (clients) {
            return clients.size();
        }
    }

    @Override
    public void close() {
        disposed = true;
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Client> all;
        synchronized (clients) {
            all = new ArrayList<>(clients.values());
            clients.clear();
        }
        for (Client client : all) {
            client.pool().dispose();
        }
        routeScheduler.dispose();
    }

    /**
     * Schließt wie {@link #close()} für neue Anfragen, lässt laufende aber zu Ende laufen, etwa wenn die Quelle nach
     * geänderten Netzwerkeinstellungen neu geöffnet wird (ADR 0015): Die Pools schließen über {@code disposeLater},
     * der Scheduler der Routenwahl über {@code disposeGracefully}.
     */
    public void closeGracefully() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        discardClients();
        routeScheduler.disposeGracefully().subscribe();
    }

    @Override
    public String toString() {
        return "WebClientConfluenceTransport[routes=" + routes + ", maxConnections=" + maxConnections + "]";
    }

    private enum Identity { CLIENT, ANONYMOUS }

    private record Client(WebClient web, ConnectionProvider pool) {
    }

    /** Effektive Route, Ziel und TLS-Identität: nie geteilter Pool zwischen verschiedenen Schlüsseln. */
    private record ClientKey(HttpRoute.Kind kind, String proxyHost, int proxyPort, String scheme, String host,
                             int port, Identity identity) {

        static ClientKey of(HttpRoute route, URI uri, Identity identity) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
            boolean proxy = route.isProxy();
            return new ClientKey(route.kind(), proxy ? route.proxyHost() : null, proxy ? route.proxyPort() : -1,
                    scheme, uri.getHost().toLowerCase(Locale.ROOT), port, identity);
        }

        String label() {
            return (kind == HttpRoute.Kind.PROXY ? "proxy" : "direct") + "-" + host + "-" + port + "-"
                    + identity.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Builder; Defaults: direkte Verbindung, JVM-Vertrauen ohne Client-Zertifikat, 15 s Verbindungs- und 60 s
     * Lese-Timeout (wie der synchrone Transport), 8 Verbindungen und 64 wartende Anfragen je Pool, 4 Threads für
     * die Routenauswahl.
     */
    public static final class Builder {

        private HttpRoutePort routes;
        private SSLContext clientTls;
        private SSLContext anonymousTls;
        private String userAgent;
        private ProxyCredentials proxyCredentials = ProxyCredentials.none();
        private Duration connectTimeout = Duration.ofSeconds(15);
        private Duration readTimeout = Duration.ofSeconds(60);
        private int maxConnections = 8;
        private int maxPending = 64;
        private int routeThreads = 4;
        private int routeQueue = 64;

        private Builder() {
        }

        /** Proxy-Route je Ziel aus der Composition Root; {@code null} = direkt. */
        public Builder routes(HttpRoutePort value) {
            this.routes = value;
            return this;
        }

        /**
         * TLS mit den Vertrauensquellen der Quelle und gegebenenfalls Client-Zertifikat, etwa aus
         * {@code ClientCertificates.contextFromKeyStore}; {@code null} = JVM-Standard.
         */
        public Builder clientTls(SSLContext value) {
            this.clientTls = value;
            return this;
        }

        /** Nur die Vertrauensquellen, für fremde Hosts ohne Client-Zertifikat; {@code null} = JVM-Standard. */
        public Builder anonymousTls(SSLContext value) {
            this.anonymousTls = value;
            return this;
        }

        public Builder userAgent(String value) {
            this.userAgent = value;
            return this;
        }

        /** Anmeldung am Proxy (#189); {@code null} ohne. */
        public Builder proxyCredentials(ProxyCredentials value) {
            this.proxyCredentials = value == null ? ProxyCredentials.none() : value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = positive(value, "connectTimeout");
            return this;
        }

        /** Höchste Wartezeit zwischen zwei Lesevorgängen einer Antwort. */
        public Builder readTimeout(Duration value) {
            this.readTimeout = positive(value, "readTimeout");
            return this;
        }

        /** Verbindungen je Pool (Route × Ziel × TLS-Identität). */
        public Builder maxConnections(int value) {
            this.maxConnections = atLeastOne(value, "maxConnections");
            return this;
        }

        /** Wartende Anfragen je Pool; darüber endet eine Anfrage sofort mit einer {@link IOException}. */
        public Builder maxPending(int value) {
            this.maxPending = atLeastOne(value, "maxPending");
            return this;
        }

        /** Threads und Warteschlange für {@link HttpRoutePort#routeFor(URI)}. */
        public Builder routeThreads(int threads, int queue) {
            this.routeThreads = atLeastOne(threads, "routeThreads");
            this.routeQueue = atLeastOne(queue, "routeQueue");
            return this;
        }

        public WebClientConfluenceTransport build() {
            return new WebClientConfluenceTransport(this);
        }

        private static Duration positive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static int atLeastOne(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1");
            }
            return value;
        }
    }
}

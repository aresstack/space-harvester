package com.aresstack.spaceharvester.source.network;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.winproxy.ProxyConfiguration;
import com.aresstack.winproxy.ProxyDiagnostics;
import com.aresstack.winproxy.PacCacheStatus;
import com.aresstack.winproxy.ProxyMode;
import com.aresstack.winproxy.ProxyResult;
import com.aresstack.winproxy.WindowsProxyResolver;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proxy-Route je Ziel-URL ({@link HttpRoutePort}) mit {@code win-proxy-java}: fragt den {@link WindowsProxyResolver}
 * im konfigurierten Modus und übersetzt dessen {@link ProxyResult} in eine {@link HttpRoute}. PROXY und DIRECT
 * bleiben so, Fehler und nicht umgesetzte Modi werden {@link HttpRoute#unavailable}: der Confluence-Transport baut
 * dann keine Verbindung auf, statt still direkt zu verbinden. Die Bibliothek erkennt die PAC-Adresse, lädt das
 * Skript und wertet {@code FindProxyForURL} mit GraalJS aus; hier wird davon nichts nachgebaut.
 *
 * <p>Eigenes dieser Klasse (wie {@code app.net.HttpRoutes} im Enterprise AI Client):
 * <ul>
 *   <li>Loopback-Ziele und {@code non-proxy-hosts} gehen immer direkt, Modus {@code disabled} ebenso.</li>
 *   <li>Die Bibliothek bekommt die URL so, wie Browser sie dem PAC-Skript geben: bei HTTPS nur
 *       {@code https://host[:port]/} (Pfad und Query sind verschlüsselt und gehen das Skript nichts an), bei HTTP
 *       die URL ohne Benutzerinfo und Fragment. Genau diese URL ist der Cache-Schlüssel, eine gecachte Route
 *       entspricht also immer der Auswertung für dieselbe Eingabe.</li>
 *   <li>Protokoll, Diagnose und Fehlerdetails zeigen Ziel- und PAC-URLs nur ohne Query und Fragment
 *       ({@link #displayUrl(String)}); auch Meldungen der Bibliothek werden so bereinigt, weil sie die Ziel-URL
 *       enthalten können. Das PAC-Skript selbst bekommt die URL unverändert.</li>
 *   <li>Routen werden {@link ProxySettings#cacheTtl()} lang gemerkt, Fehler {@link ProxySettings#failureTtl()} lang
 *       (höchstens {@value #MAX_CACHE_ENTRIES} Einträge). Scheitert die erneute Auflösung einer Route, die zuvor
 *       gefunden wurde (z. B. PAC-Download mit Zeitüberschreitung), bleibt die zuletzt gültige Route und wird nach
 *       {@link ProxySettings#failureTtl()} erneut versucht; UNAVAILABLE gibt es nur ohne gültige Vorgängerin. Das PAC-Skript selbst hält die Bibliothek
 *       (ab 0.3.0) zwischengespeichert: Lebensdauer laut HTTP-Kopfzeilen des PAC-Servers, sonst 12 h; bei Ladefehler
 *       bleibt das zuletzt gültige Skript ({@link #pacStatus()}). {@link #invalidate()} und {@link #routeFailed}
 *       lassen es neu laden.</li>
 *   <li>Jede Auflösung läuft auf einem eigenen Pool von höchstens {@value #MAX_RESOLVER_THREADS} Threads mit der Frist
 *       {@link ProxySettings#resolveTimeout()}; ist der Pool voll oder die Frist überschritten, ist die Route
 *       UNAVAILABLE. Die erste Auflösung geschieht bei der ersten Anfrage, nie beim Start.</li>
 *   <li>{@link #reconfigure} stellt Einstellungen und Resolver zur Laufzeit um (ADR 0015) und verwirft alle gemerkten
 *       Routen; wer diese Instanz hält, bekommt beim nächsten Verbindungsaufbau die neue Route.</li>
 * </ul>
 */
public final class ProxyRoutes implements HttpRoutePort, RouteFailureListener {

    /** Zugriff auf die Bibliothek; Naht für Tests ohne PowerShell. */
    public interface Resolver {
        ProxyResult resolve(String url);

        ProxyDiagnostics diagnose(String url);

        /** Lässt PAC-URL und PAC-Skript bei der nächsten Auflösung neu laden; Vorgabe: nichts. */
        default void invalidatePac() {
        }

        /** Zustand des zwischengespeicherten PAC-Skripts, leer ohne PAC; Vorgabe: leer. */
        default java.util.Optional<PacCacheStatus> pacStatus() {
            return java.util.Optional.empty();
        }
    }

    static final int MAX_CACHE_ENTRIES = 1024;
    static final int MAX_RESOLVER_THREADS = 4;
    static final String REASON_LOOPBACK = "loopback";
    static final String REASON_NON_PROXY_HOST = "non-proxy-host";
    static final String REASON_DISABLED = "disabled";
    static final String REASON_TIMEOUT = "resolve-timeout";
    static final String REASON_BUSY = "resolver-busy";
    static final String REASON_RESOLVER_FAILED = "resolver-failed";

    private static final Logger LOG = LoggerFactory.getLogger(ProxyRoutes.class);
    private static final AtomicInteger THREADS = new AtomicInteger();

    /** Einstellungen und Resolver; {@link #reconfigure} ersetzt beide zusammen unter der Sperre des Caches. */
    private volatile State state;
    private final LongSupplier clock;
    private final ThreadPoolExecutor pool;
    /** Frühester Zeitpunkt, zu dem eine gescheiterte Proxy-Verbindung das PAC-Skript erneut neu laden lässt. */
    private long nextPacReloadAt;
    /** Wer über Änderungen des PAC-Zustands ({@link #pacState()}) Bescheid bekommt; kein Abfragen nötig (ADR 0027). */
    private final List<Runnable> pacListeners = new CopyOnWriteArrayList<>();
    /** Zuletzt gemeldeter PAC-Zustand; nur unter {@code pacListeners} als Sperre. */
    private PacState lastPac = PacState.NONE;
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private ProxyRoutes(ProxySettings settings, Resolver resolver, LongSupplier clock) {
        this.state = new State(settings, resolver);
        this.clock = clock;
        this.pool = new ThreadPoolExecutor(0, MAX_RESOLVER_THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "proxy-resolve-" + THREADS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Produktiv: prüft die Einstellungen und baut den Resolver der Bibliothek.
     *
     * @throws InvalidNetworkConfigurationException mit allen gefundenen Problemen
     */
    public static ProxyRoutes from(ProxySettings settings) {
        return create(settings, library(settings), System::currentTimeMillis);
    }

    /**
     * Probleme der Einstellungen auf diesem System, leer wenn {@link #from} und {@link #reconfigure} sie annehmen; ohne
     * etwas aufzulösen.
     */
    public static List<String> problems(ProxySettings settings) {
        return validationProblems(settings, isWindows());
    }

    /**
     * Stellt auf neue Einstellungen um (ADR 0015): prüft sie, baut den Resolver der Bibliothek neu und verwirft alle
     * gemerkten Routen samt PAC-Ergebnissen. Laufende Auflösungen enden mit den bisherigen Einstellungen, ihr Ergebnis
     * wird nicht mehr gemerkt; die nächste Anfrage löst neu auf. Eine nicht bestimmbare Route bleibt UNAVAILABLE.
     *
     * @throws InvalidNetworkConfigurationException mit allen gefundenen Problemen; dann bleibt alles unverändert
     */
    public void reconfigure(ProxySettings settings) {
        reconfigure(settings, library(settings));
    }

    /** Wie {@link #reconfigure(ProxySettings)} mit eigenem Resolver, ohne Prüfung der Einstellungen (Tests). */
    void reconfigure(ProxySettings settings, Resolver resolver) {
        if (settings == null || resolver == null) {
            throw new IllegalArgumentException("settings and resolver must not be null");
        }
        synchronized (cache) {
            state = new State(settings, resolver);
            cache.clear();
        }
        LOG.info("Proxy-Route umgestellt, Modus {}", modeName(settings.mode()));
        pacChanged();
    }

    /** Prüft die Einstellungen und baut den Resolver der Bibliothek. */
    private static Resolver library(ProxySettings settings) {
        List<String> problems = problems(settings);
        if (!problems.isEmpty()) {
            throw new InvalidNetworkConfigurationException(problems);
        }
        WindowsProxyResolver library = new WindowsProxyResolver(libraryConfiguration(settings));
        return new Resolver() {
            @Override
            public ProxyResult resolve(String url) {
                return library.resolve(url);
            }

            @Override
            public ProxyDiagnostics diagnose(String url) {
                return library.diagnose(url);
            }

            @Override
            public void invalidatePac() {
                library.invalidatePacCache();
            }

            @Override
            public java.util.Optional<PacCacheStatus> pacStatus() {
                return java.util.Optional.of(library.pacCacheStatus());
            }
        };
    }

    /** Mit eigenem Resolver und eigener Zeitquelle, ohne Prüfung der Einstellungen (Tests). */
    public static ProxyRoutes create(ProxySettings settings, Resolver resolver, LongSupplier clock) {
        if (settings == null || resolver == null || clock == null) {
            throw new IllegalArgumentException("settings, resolver and clock must not be null");
        }
        return new ProxyRoutes(settings, resolver, clock);
    }

    /** Probleme der Einstellungen, leer wenn der Modus so laufen kann. */
    static List<String> validationProblems(ProxySettings settings, boolean windows) {
        List<String> problems = new ArrayList<>();
        switch (settings.mode()) {
            case MANUAL_PROXY -> {
                if (settings.proxyHost() == null || settings.proxyHost().isBlank()) {
                    problems.add("mode manual-proxy braucht proxy-host");
                }
                if (settings.proxyPort() == null || settings.proxyPort() < 1 || settings.proxyPort() > 65535) {
                    problems.add("mode manual-proxy braucht proxy-port zwischen 1 und 65535");
                }
            }
            case PAC_URL_MANUAL -> {
                URI pac = settings.pacUrl();
                if (pac == null) {
                    problems.add("mode pac-url-manual braucht pac-url");
                } else if (pac.getScheme() == null || !List.of("http", "https", "file")
                        .contains(pac.getScheme().toLowerCase(Locale.ROOT))) {
                    problems.add("pac-url muss mit http://, https:// oder file:// beginnen");
                }
            }
            case PAC_URL_POWERSHELL, PAC_URL_WINDOWS_SETTINGS, PAC_URL_WSCRIPT, WINDOWS_STATIC_PROXY -> {
                if (!windows) {
                    problems.add("mode " + modeName(settings.mode()) + " liest die Windows-Einstellungen des "
                            + "Dienstkontos und läuft nur unter Windows; ohne Windows pac-url-manual oder "
                            + "manual-proxy verwenden");
                }
            }
            case DISABLED -> { }
        }
        if (settings.mode() != ProxySettings.Mode.PAC_URL_MANUAL && settings.pacUrl() != null) {
            problems.add("pac-url gilt nur für mode pac-url-manual");
        }
        if (settings.mode() != ProxySettings.Mode.MANUAL_PROXY
                && (settings.proxyHost() != null || settings.proxyPort() != null)) {
            problems.add("proxy-host/proxy-port gelten nur für mode manual-proxy");
        }
        if (settings.resolveTimeout().isNegative() || settings.resolveTimeout().isZero()) {
            problems.add("resolve-timeout muss positiv sein");
        }
        if (settings.cacheTtl().isNegative() || settings.failureTtl().isNegative()) {
            problems.add("cache-ttl und failure-ttl dürfen nicht negativ sein");
        }
        if (settings.pacDiscoveryScript() != null && settings.mode() != ProxySettings.Mode.PAC_URL_POWERSHELL
                && settings.mode() != ProxySettings.Mode.PAC_URL_WSCRIPT) {
            problems.add("pac-discovery-script gilt nur für mode pac-url-powershell und pac-url-wscript");
        }
        ProxySettings.Authentication authentication = settings.authentication();
        if (authentication != null) {
            if (settings.mode() == ProxySettings.Mode.DISABLED) {
                problems.add("auth gilt nur mit Proxy, nicht mit mode disabled");
            }
            if (authentication.username() != null && authentication.username().indexOf(':') >= 0) {
                problems.add("auth.username darf für BASIC keinen Doppelpunkt enthalten");
            }
        }
        for (String host : settings.nonProxyHosts()) {
            if (host == null || host.isBlank()) {
                problems.add("non-proxy-hosts enthält einen leeren Eintrag");
            }
        }
        if (problems.isEmpty() && settings.mode() != ProxySettings.Mode.DISABLED) {
            problems.addAll(libraryConfiguration(settings).validationProblems());
        }
        return problems;
    }

    static ProxyConfiguration libraryConfiguration(ProxySettings settings) {
        // Die Test-URL wird immer gesetzt, sonst nähme die Bibliothek eine eigene Vorgabe mit fremdem Host; sie zählt
        // nur bei leerem Ziel, und das gibt es hier nicht.
        ProxyConfiguration.Builder builder = ProxyConfiguration.builder()
                .mode(libraryMode(settings.mode()))
                .testUrl("http://127.0.0.1/");
        if (settings.pacUrl() != null) {
            builder.pacUrl(settings.pacUrl().toString());
        }
        if (settings.proxyHost() != null) {
            builder.manualProxyHost(settings.proxyHost());
        }
        if (settings.proxyPort() != null) {
            builder.manualProxyPort(settings.proxyPort());
        }
        if (settings.pacDiscoveryScript() != null) {
            builder.pacUrlDiscoveryScript(settings.pacDiscoveryScript());
        }
        return builder.build();
    }

    static ProxyMode libraryMode(ProxySettings.Mode mode) {
        return ProxyMode.valueOf(mode.name());
    }

    @Override
    public HttpRoute routeFor(URI target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        State current = state;
        ProxySettings settings = current.settings();
        HttpRoute fixed = fixedRoute(settings, target.getHost());
        if (fixed != null) {
            return fixed;
        }
        String url = resolutionUrl(target);
        long now = clock.getAsLong();
        synchronized (cache) {
            Entry cached = state == current ? cache.get(url) : null;
            if (cached != null && cached.expiresAt > now) {
                return cached.route;
            }
        }
        HttpRoute route = resolve(current, url);
        // Nur eine Auflösung lädt das PAC-Skript nach; danach kann sich sein Zustand geändert haben.
        pacChanged();
        String shown = displayUrl(url);
        long ttl = (route.isUnavailable() ? settings.failureTtl() : settings.cacheTtl()).toMillis();
        HttpRoute kept = null;
        synchronized (cache) {
            // Nach einem Wechsel nie die Route der alten Einstellungen merken.
            if (state == current) {
                Entry previous = cache.get(url);
                if (route.isUnavailable() && previous != null && !previous.route.isUnavailable()) {
                    // Zuletzt gültige Route behalten: ein kurz nicht erreichbarer WPAD-Server bricht keine
                    // funktionierende Verbindung ab; nach failure-ttl wird erneut aufgelöst.
                    kept = previous.route;
                }
                cache.put(url, new Entry(kept != null ? kept : route, now + ttl));
            }
        }
        if (kept != null) {
            LOG.warn("Proxy-Route für {} ({}) nicht neu bestimmbar, zuletzt gültige bleibt: {} ({})", shown,
                    modeName(settings.mode()), kept.describe(), route.describe());
            return kept;
        }
        if (route.isUnavailable()) {
            LOG.warn("Proxy-Route für {} ({}) nicht bestimmbar: {}", shown, modeName(settings.mode()), route.describe());
        } else {
            LOG.info("Proxy-Route für {} ({}): {}", shown, modeName(settings.mode()), route.describe());
        }
        return route;
    }

    /** Vergisst alle gemerkten Routen und lässt das PAC-Skript neu laden, z. B. nach einem Netzwechsel. */
    public void invalidate() {
        State current;
        synchronized (cache) {
            cache.clear();
            current = state;
        }
        current.resolver().invalidatePac();
    }

    /**
     * Eine Verbindung über die gelieferte Proxy-Route ist gescheitert: die gemerkte Route dieses Ziels wird verworfen und
     * das PAC-Skript beim nächsten Mal neu geladen, höchstens einmal je {@link ProxySettings#failureTtl()}, damit ein
     * ausgefallener Proxy den PAC-Server nicht mit Abrufen überzieht. Scheitert das Neuladen, bleibt das zuletzt gültige
     * Skript in Gebrauch.
     */
    @Override
    public void routeFailed(URI target, HttpRoute route) {
        if (target == null || route == null || !route.isProxy()) {
            return;
        }
        State current = state;
        if (fixedRoute(current.settings(), target.getHost()) != null) {
            return;
        }
        String url = resolutionUrl(target);
        long now = clock.getAsLong();
        boolean reload;
        synchronized (cache) {
            if (state != current) {
                return;
            }
            Entry cached = cache.get(url);
            if (cached != null && cached.route.equals(route)) {
                cache.remove(url);
            }
            reload = now >= nextPacReloadAt;
            if (reload) {
                nextPacReloadAt = now + Math.max(1, current.settings().failureTtl().toMillis());
            }
        }
        if (reload) {
            LOG.info("Verbindung über {} zu {} gescheitert, Proxy-Skript wird neu geladen", route.describe(),
                    displayUrl(url));
            current.resolver().invalidatePac();
        }
    }

    /**
     * Zustand des PAC-Skripts in einer Zeile, leer ohne PAC-Modus oder solange keines geladen wurde; mit
     * {@code nicht erneuert seit …}, wenn das Erneuern scheitert und das zuletzt gültige Skript weiter gilt.
     */
    public Optional<String> pacStatus() {
        return state.resolver().pacStatus()
                .filter(status -> status.getPacUrl() != null || status.getRefreshFailingSince() > 0)
                .map(ProxyRoutes::describePac);
    }

    /** {@code true}, solange das Erneuern des PAC-Skripts scheitert und das zuletzt gültige weiter gilt. */
    public boolean pacStale() {
        return state.resolver().pacStatus().map(PacCacheStatus::isStale).orElse(false);
    }

    /**
     * Zustand des PAC-Skripts für Anzeigen (Angelo, 16.11.2026: „Wie erkenne ich, dass ich Probleme habe?“).
     *
     * @param stale        ob das Erneuern scheitert und das zuletzt gültige Skript weiter gilt
     * @param failingSince seit wann das Erneuern scheitert, {@code null} sonst
     * @param description  die Zeile aus {@link #pacStatus()} mit Grund, {@code null} ohne PAC
     */
    public record PacState(boolean stale, java.time.Instant failingSince, String description) {

        /** Ohne PAC oder solange keines geladen wurde. */
        public static final PacState NONE = new PacState(false, null, null);
    }

    /** Der aktuelle PAC-Zustand; {@link PacState#NONE} ohne PAC. */
    public PacState pacState() {
        return state.resolver().pacStatus()
                .filter(status -> status.getPacUrl() != null || status.getRefreshFailingSince() > 0)
                .map(status -> new PacState(status.isStale(), status.getRefreshFailingSince() > 0
                        ? java.time.Instant.ofEpochMilli(status.getRefreshFailingSince()) : null,
                        describePac(status)))
                .orElse(PacState.NONE);
    }

    /**
     * Meldet jede Änderung von {@link #pacState()}, etwa wenn das Erneuern des Skripts zu scheitern beginnt oder
     * wieder gelingt; gemeldet wird auf dem Thread, der die Route auflöst. Das Schließen beendet die Meldungen.
     */
    public AutoCloseable onPacChange(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        pacListeners.add(listener);
        return () -> pacListeners.remove(listener);
    }

    /** Vergleicht den PAC-Zustand mit dem zuletzt gemeldeten und benachrichtigt bei Änderung. */
    private void pacChanged() {
        PacState now = pacState();
        boolean changed;
        synchronized (pacListeners) {
            changed = !now.equals(lastPac);
            lastPac = now;
        }
        if (changed) {
            for (Runnable listener : pacListeners) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    LOG.warn("PAC-Beobachter scheiterte: {}", e.toString());
                }
            }
        }
    }

    static String describePac(PacCacheStatus status) {
        java.time.format.DateTimeFormatter time = java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")
                .withZone(java.time.ZoneId.systemDefault());
        StringBuilder text = new StringBuilder("Proxy-Skript ");
        if (status.getPacUrl() != null) {
            text.append(displayUrl(status.getPacUrl())).append(" geladen ")
                    .append(time.format(java.time.Instant.ofEpochMilli(status.getLoadedAt())))
                    .append(", gültig bis ").append(time.format(java.time.Instant.ofEpochMilli(status.getValidUntil())));
        } else {
            text.append("nicht geladen");
        }
        if (status.getRefreshFailingSince() > 0) {
            text.append("; nicht erneuert seit ")
                    .append(time.format(java.time.Instant.ofEpochMilli(status.getRefreshFailingSince())))
                    .append(" (").append(status.getConsecutiveFailures()).append(" Versuche)");
            if (status.getLastFailure() != null) {
                text.append(": ").append(displayUrl(status.getLastFailure()));
            }
        }
        return text.toString();
    }

    /**
     * Die Auflösung mit allen Schritten der Bibliothek, ohne Cache: {@code Resolving <url> ...}, je Schritt eine
     * Zeile, zuletzt {@code Result: ...}. Für Betrieb und Fehlersuche, siehe {@link ProxyDiagnosticsMain}.
     */
    public List<String> diagnose(URI target) {
        State current = state;
        ProxySettings settings = current.settings();
        List<String> lines = new ArrayList<>();
        String url = resolutionUrl(target);
        lines.add("Resolving " + displayUrl(url) + " ...");
        lines.add("Mode: " + modeName(settings.mode()));
        HttpRoute fixed = fixedRoute(settings, target.getHost());
        if (fixed != null) {
            lines.add("Result: " + fixed.describe());
            return lines;
        }
        HttpRoute route;
        try {
            ProxyDiagnostics diagnostics = withTimeout(settings, () -> current.resolver().diagnose(url));
            diagnostics.getSteps().forEach(step -> lines.add("- " + scrub(settings, step, url)));
            if (diagnostics.getFailureDetail() != null && !diagnostics.getFailureDetail().isEmpty()) {
                lines.add("Failure: " + scrub(settings, diagnostics.getFailureDetail(), url));
            }
            pacStatus().ifPresent(lines::add);
            route = scrub(settings, toRoute(diagnostics.getResult()), url);
            lines.add("Result: " + route.describe() + " [" + diagnostics.getDurationMillis() + " ms]");
            return lines;
        } catch (TimeoutException e) {
            route = HttpRoute.unavailable(REASON_TIMEOUT, timeoutDetail(settings));
        } catch (RejectedExecutionException e) {
            route = HttpRoute.unavailable(REASON_BUSY, busyDetail());
        } catch (Exception e) {
            route = HttpRoute.unavailable(REASON_RESOLVER_FAILED, scrub(settings, failureDetail(e), url));
        }
        lines.add("Result: " + route.describe());
        return lines;
    }

    /** Konfiguration in einer Zeile für das Startprotokoll, ohne etwas aufzulösen. */
    public String describe() {
        ProxySettings settings = state.settings();
        StringBuilder text = new StringBuilder(modeName(settings.mode()));
        switch (settings.mode()) {
            case MANUAL_PROXY -> text.append(' ').append(settings.proxyHost()).append(':').append(settings.proxyPort());
            case PAC_URL_MANUAL -> text.append(' ').append(displayUrl(settings.pacUrl().toString()));
            case PAC_URL_POWERSHELL, PAC_URL_WSCRIPT -> text.append(settings.pacDiscoveryScript() == null
                    ? " (Standardskript)" : " (eigenes Skript)");
            default -> { }
        }
        if (settings.authentication() != null) {
            text.append(", Anmeldung BASIC");
        }
        if (!settings.nonProxyHosts().isEmpty()) {
            text.append(", ohne Proxy: ").append(settings.nonProxyHosts());
        }
        if (settings.mode() != ProxySettings.Mode.DISABLED) {
            text.append(", Frist ").append(settings.resolveTimeout().toMillis()).append(" ms");
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return "ProxyRoutes[" + describe() + "]";
    }

    private static HttpRoute fixedRoute(ProxySettings settings, String host) {
        if (isLoopback(host)) {
            return HttpRoute.direct(REASON_LOOPBACK);
        }
        if (matchesNonProxyHost(settings, host)) {
            return HttpRoute.direct(REASON_NON_PROXY_HOST);
        }
        if (settings.mode() == ProxySettings.Mode.DISABLED) {
            return HttpRoute.direct(REASON_DISABLED);
        }
        return null;
    }

    private HttpRoute resolve(State current, String url) {
        ProxySettings settings = current.settings();
        try {
            return scrub(settings, toRoute(withTimeout(settings, () -> current.resolver().resolve(url))), url);
        } catch (TimeoutException e) {
            return HttpRoute.unavailable(REASON_TIMEOUT, timeoutDetail(settings));
        } catch (RejectedExecutionException e) {
            return HttpRoute.unavailable(REASON_BUSY, busyDetail());
        } catch (Exception e) {
            // Ohne Stacktrace: Ausnahmemeldungen der Bibliothek können die Ziel-URL samt Query enthalten.
            String detail = scrub(settings, failureDetail(e), url);
            LOG.warn("Proxy-Auflösung für {} gescheitert: {}", displayUrl(url), detail);
            return HttpRoute.unavailable(REASON_RESOLVER_FAILED, detail);
        }
    }

    static HttpRoute toRoute(ProxyResult result) {
        if (result == null) {
            return HttpRoute.unavailable(REASON_RESOLVER_FAILED, "kein Ergebnis");
        }
        String reason = result.getReason() == null ? result.getKind().name().toLowerCase(Locale.ROOT)
                : result.getReason();
        String detail = result.getDetail() == null ? "" : result.getDetail();
        return switch (result.getKind()) {
            case PROXY -> HttpRoute.proxy(result.getHost(), result.getPort(), reason);
            case DIRECT -> HttpRoute.direct(reason);
            case NOT_IMPLEMENTED -> HttpRoute.unavailable(reason, "Modus in win-proxy-java nicht umgesetzt"
                    + (detail.isEmpty() ? "" : ": " + detail));
            default -> HttpRoute.unavailable(reason, detail);
        };
    }

    private <T> T withTimeout(ProxySettings settings, Callable<T> work) throws Exception {
        Future<T> future = pool.submit(work);
        try {
            return future.get(settings.resolveTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception exception ? exception : e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Die URL, die das PAC-Skript sieht und unter der die Route gemerkt wird: bei HTTPS nur Schema, Host und
     * abweichender Port, sonst die URL ohne Benutzerinfo und Fragment.
     */
    static String resolutionUrl(URI target) {
        String scheme = target.getScheme() == null ? "http" : target.getScheme().toLowerCase(Locale.ROOT);
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        int port = target.getPort();
        boolean defaultPort = port < 0 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        StringBuilder url = new StringBuilder(scheme).append("://").append(host);
        if (!defaultPort) {
            url.append(':').append(port);
        }
        if ("https".equals(scheme)) {
            return url.append('/').toString();
        }
        String path = target.getRawPath();
        url.append(path == null || path.isEmpty() ? "/" : path);
        if (target.getRawQuery() != null) {
            url.append('?').append(target.getRawQuery());
        }
        return url.toString();
    }

    /**
     * Die URL für Protokoll, Diagnose und Fehlermeldungen: ohne Benutzerinfo, Query und Fragment; eine vorhandene
     * Query wird als {@code ?…} angedeutet.
     */
    static String displayUrl(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return url.replaceAll("[?#].*$", "");
            }
            String host = uri.getHost() != null ? uri.getHost() : "";
            StringBuilder text = new StringBuilder(uri.getScheme()).append("://").append(host);
            if (uri.getPort() >= 0) {
                text.append(':').append(uri.getPort());
            }
            text.append(uri.getRawPath() == null ? "" : uri.getRawPath());
            return uri.getRawQuery() == null ? text.toString() : text.append("?…").toString();
        } catch (IllegalArgumentException e) {
            return url.replaceAll("[?#].*$", "");
        }
    }

    /** Entfernt Ziel-URL und PAC-URL samt Query aus einem Text der Bibliothek. */
    private static String scrub(ProxySettings settings, String text, String url) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = scrubUrl(text, url);
        return settings.pacUrl() == null ? result : scrubUrl(result, settings.pacUrl().toString());
    }

    private static String scrubUrl(String text, String url) {
        String result = text.replace(url, displayUrl(url));
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return result;
        }
        for (String part : new String[] {uri.getRawQuery(), uri.getQuery(), uri.getRawUserInfo(), uri.getUserInfo()}) {
            if (part != null && !part.isEmpty()) {
                result = result.replace(part, "…");
            }
        }
        return result;
    }

    private static HttpRoute scrub(ProxySettings settings, HttpRoute route, String url) {
        if (!route.isUnavailable()) {
            return route;
        }
        return HttpRoute.unavailable(scrub(settings, route.reason(), url), scrub(settings, route.detail(), url));
    }

    private static boolean matchesNonProxyHost(ProxySettings settings, String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        for (String pattern : settings.nonProxyHosts()) {
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("*.") ? name.endsWith(p.substring(1)) : name.equals(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLoopback(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (name.equalsIgnoreCase("localhost")) {
            return true;
        }
        // Nur IP-Literale prüfen, keine DNS-Auflösung des Ziels.
        if (name.matches("[0-9.]+") || name.contains(":")) {
            try {
                return InetAddress.getByName(name).isLoopbackAddress();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    static String modeName(ProxySettings.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String timeoutDetail(ProxySettings settings) {
        return "keine Antwort der Proxy-Auflösung innerhalb von " + settings.resolveTimeout().toMillis()
                + " ms (space-harvester.network.proxy.resolve-timeout)";
    }

    private static String busyDetail() {
        return "zu viele gleichzeitige Proxy-Auflösungen";
    }

    private static String failureDetail(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private record Entry(HttpRoute route, long expiresAt) {
    }

    private record State(ProxySettings settings, Resolver resolver) {
    }
}

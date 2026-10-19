package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.source.confluence.AsyncSnapshotTransports;
import com.aresstack.spaceharvester.source.confluence.DelegatedConfluenceHttp;
import com.aresstack.spaceharvester.source.network.ConnectionProbe;
import com.aresstack.spaceharvester.source.network.InvalidNetworkConfigurationException;
import com.aresstack.spaceharvester.source.network.ProxyAuthenticator;
import com.aresstack.spaceharvester.source.network.ProxyCredentials;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baut aus der Konfiguration die Quellen und prüft sie dabei vollständig: Fehler aller Quellen werden gesammelt
 * und gemeinsam gemeldet, damit ein Start nicht an einer Quelle nach der anderen scheitert. Verbindungen werden erst
 * bei der ersten Anfrage aufgebaut; der Dienst startet also auch, wenn eine Quelle gerade nicht erreichbar ist.
 */
public final class ConfiguredSourceCatalog implements SourceCatalog, AutoCloseable {

    /**
     * Quellentypen ohne eigene Registrierung: Confluence, lokale Dateien und die Kopien aus dem Enterprise AI Client
     * ohne eigenen Ordner ({@link EacSourceAdapters}: ftp, jes, ndv, sharepoint, betaview, outlook) und MediaWiki (#187).
     */
    public static final List<SourceAdapter> DEFAULT_ADAPTERS = List.of(new ConfluenceSourceAdapter(),
            new LocalFilesSourceAdapter(), EacSourceAdapters.ftp(), EacSourceAdapters.jes(), EacSourceAdapters.ndv(),
            EacSourceAdapters.sharePoint(), EacSourceAdapters.betaView(),
            EacSourceAdapters.outlook(), MediaWikiSources.adapter());

    /** Quellentypen, die der Space Harvester ohne eigene Registrierung kennt. */
    public static final Set<String> TYPES = DEFAULT_ADAPTERS.stream().map(SourceAdapter::type)
            .collect(Collectors.toUnmodifiableSet());

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredSourceCatalog.class);

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /** Transport für native Seiten-Snapshots ({@code space-harvester.network.async-transport}, #47). */
    public enum AsyncTransport {
        /** Nur der synchrone Transport (Rückfallpfad). */
        DISABLED,
        /** Snapshots nicht blockierend über Spring WebClient auf Reactor Netty (Standard des Dienstes). */
        WEB_CLIENT
    }

    private final Map<String, SourceAdapter> adapters;
    private final ConfiguredSecrets secrets;
    private final SourceAdapter.Context context;
    private final AsyncSnapshotTransports async;
    private final ProxyRoutes routes;
    /**
     * Die geöffneten Quellen in Konfigurationsreihenfolge; zur Laufzeit (#188) als Ganzes unter {@code this} ersetzt,
     * gelesen ohne Sperre.
     */
    private volatile Map<String, Opened> opened;
    private final ProxyAuthenticator authenticator;

    /**
     * Eine geöffnete Quelle mit ihren Einstellungen. Eine abgeschaltete ({@code enabled = false}) behält Einstellungen
     * und Daten, ist für Suche, Lesen, Aufträge und Export aber unbekannt.
     */
    private record Opened(SourceSettingsConfig config, HarvestSource source, DelegatedConfluenceHttp delegated,
                          URI baseUrl, boolean enabled) {

        Opened enabled(boolean value) {
            return new Opened(config, source, delegated, baseUrl, value);
        }

        void close() {
            if (delegated != null) {
                delegated.close();
            }
        }
    }

    private ConfiguredSourceCatalog(Map<String, SourceAdapter> adapters, ConfiguredSecrets secrets,
                                    SourceAdapter.Context context, Map<String, Opened> opened,
                                    ProxyAuthenticator authenticator) {
        this.authenticator = authenticator;
        this.adapters = adapters;
        this.secrets = secrets;
        this.context = context;
        this.async = context.async();
        this.routes = context.routes();
        this.opened = opened;
    }

    /** Ohne eigene Vertrauensquellen und ohne Proxy. */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent) {
        return of(configs, userAgent, TrustBundles.NONE, ProxySettings.disabled());
    }

    /** Ohne Proxy. */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent,
                                             TrustBundles trustBundles) {
        return of(configs, userAgent, trustBundles, ProxySettings.disabled());
    }

    /** Ohne eigene Vertrauensquellen. */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent,
                                             ProxySettings proxy) {
        return of(configs, userAgent, TrustBundles.NONE, proxy);
    }

    /**
     * @param userAgent    User-Agent für HTTP-Aufrufe der Adapter
     * @param trustBundles Vertrauensquellen für {@code trust-bundle}
     * @param proxy        Proxy-Einstellungen für alle Quellen
     * @throws InvalidNetworkConfigurationException bei fehlerhaften Proxy-Einstellungen
     * @throws InvalidSourceConfigurationException mit allen gefundenen Problemen der Quellen
     */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent,
                                             TrustBundles trustBundles, ProxySettings proxy) {
        return of(configs, userAgent, trustBundles, proxy, AsyncTransport.DISABLED);
    }

    /**
     * @param asyncTransport {@link AsyncTransport#WEB_CLIENT}: Quellen liefern native Seiten-Snapshots zusätzlich
     *                       nicht blockierend ({@link HarvestSource#storedPageAsync})
     */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent,
                                             TrustBundles trustBundles, ProxySettings proxy,
                                             AsyncTransport asyncTransport) {
        return of(configs, userAgent, trustBundles, proxy, asyncTransport, DEFAULT_ADAPTERS);
    }

    /**
     * @param adapters Quellentypen, die der Katalog kennt (#18); ein Typ je Adapter, sonst
     *                 {@link IllegalArgumentException}
     */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent,
                                             TrustBundles trustBundles, ProxySettings proxy,
                                             AsyncTransport asyncTransport, List<? extends SourceAdapter> adapters) {
        return of(configs, userAgent, trustBundles, proxy, asyncTransport, adapters, null);
    }

    /**
     * @param secretProvider Zugangsdaten des einbettenden Programms (#188) unter {@code source:<id>} (Anmeldung) und
     *                       {@code source:<id>:client-certificate} (Passwort der PKCS#12-Datei), gefragt erst beim
     *                       ersten Zugriff und nur, wenn die Konfiguration keine nennt; {@code null} ohne. Dann darf
     *                       eine Quelle mit Anmeldung Passwort oder Token weglassen.
     */
    public static ConfiguredSourceCatalog of(List<SourceSettingsConfig> configs, String userAgent,
                                             TrustBundles trustBundles, ProxySettings proxy,
                                             AsyncTransport asyncTransport, List<? extends SourceAdapter> adapters,
                                             SourceSecrets secretProvider) {
        Map<String, SourceAdapter> byType = new LinkedHashMap<>();
        for (SourceAdapter adapter : adapters) {
            if (byType.putIfAbsent(adapter.type(), adapter) != null) {
                throw new IllegalArgumentException("Quellentyp doppelt registriert: " + adapter.type());
            }
        }
        ProxyRoutes routes = ProxyRoutes.from(proxy);
        LOG.info("Proxy-Route der Quellen: {}", routes.describe());
        List<String> problems = new ArrayList<>();
        ConfiguredSecrets secrets = new ConfiguredSecrets(secretProvider);
        ProxyCredentials proxyCredentials = ProxyCredentials.of(proxy.authentication(), secretProvider);
        AsyncSnapshotTransports async = asyncTransport == AsyncTransport.WEB_CLIENT
                ? new AsyncSnapshotTransports(secrets, routes, userAgent, proxyCredentials) : null;
        LOG.info("Seiten-Snapshots: {}", async == null ? "synchron" : "asynchron über WebClient");
        SourceAdapter.Context context = new SourceAdapter.Context(secrets, routes, userAgent, trustBundles, async,
                proxyCredentials);
        Map<String, Opened> opened = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < configs.size(); i++) {
            SourceSettingsConfig config = configs.get(i);
            String label = "sources[" + i + "]" + (config.id() == null ? "" : " (id=" + config.id() + ")");
            List<String> own = check(config, seenIds, byType, secrets.external());
            if (config.id() != null) {
                seenIds.add(config.id());
            }
            if (!own.isEmpty()) {
                own.forEach(problem -> problems.add(label + ": " + problem));
                continue;
            }
            try {
                opened.put(config.id(), open(config, byType.get(config.type()), context));
            } catch (InvalidSourceConfigurationException e) {
                e.problems().forEach(problem -> problems.add(label + ": " + problem));
            }
        }
        if (!problems.isEmpty()) {
            opened.values().forEach(Opened::close);
            if (async != null) {
                async.close();
            }
            throw new InvalidSourceConfigurationException(problems);
        }
        List<String> duplicates = sameTargets(configs);
        if (!duplicates.isEmpty()) {
            // Beim Start nur eine Warnung, damit bestehende Konfigurationen weiter starten; neue lehnt problems() ab.
            LOG.warn("Quellen mit demselben Ziel (Typ und Basisadresse): {}", String.join(", ", duplicates));
        }
        // Für HttpURLConnection (synchroner Transport, KI-Dienst) gibt es die Proxy-Anmeldung nur prozessweit.
        ProxyAuthenticator authenticator = ProxyAuthenticator.install(proxyCredentials);
        return new ConfiguredSourceCatalog(Collections.unmodifiableMap(byType), secrets, context,
                Collections.unmodifiableMap(opened), authenticator);
    }

    private static Opened open(SourceSettingsConfig config, SourceAdapter adapter, SourceAdapter.Context context) {
        String title = config.title() == null || config.title().isBlank() ? config.id() : config.title();
        SourceAdapter.OpenedSource source = adapter.open(config, context);
        URI baseUrl = config.baseUrl();
        boolean http = baseUrl != null && baseUrl.getScheme() != null
                && List.of("http", "https").contains(baseUrl.getScheme().toLowerCase(Locale.ROOT));
        return new Opened(config, new KnowledgeSourceHarvestSource(config.id(), config.type(), title, source.port(),
                context.async() == null ? null : context.async().inFlight(), source.scope()), source.delegated(),
                http ? baseUrl : null, true);
    }

    /** Eigene Probleme und die des Typs; ohne etwas zu öffnen. */
    private static List<String> check(SourceSettingsConfig config, Set<String> knownIds,
                                      Map<String, SourceAdapter> adapters, boolean externalSecrets) {
        List<String> problems = new ArrayList<>(checkIdentity(config, knownIds, adapters));
        SourceAdapter adapter = config.type() == null ? null : adapters.get(config.type());
        if (adapter != null) {
            problems.addAll(adapter.check(config, externalSecrets));
        }
        return problems;
    }

    private static List<String> checkIdentity(SourceSettingsConfig config, Set<String> knownIds,
                                              Map<String, SourceAdapter> adapters) {
        List<String> problems = new ArrayList<>();
        if (config.id() == null || !ID.matcher(config.id()).matches()) {
            problems.add("id fehlt oder ist ungültig (erlaubt: Kleinbuchstaben, Ziffern, Bindestrich; z. B. "
                    + "'confluence')");
        } else if (knownIds.contains(config.id())) {
            problems.add("id ist doppelt vergeben");
        }
        if (config.type() == null || !adapters.containsKey(config.type())) {
            problems.add("type fehlt oder ist unbekannt (bekannt: " + String.join(", ", adapters.keySet()) + ")");
        }
        return problems;
    }

    /** Die eingeschalteten Quellen; abgeschaltete nehmen an nichts teil. */
    @Override
    public List<HarvestSource> all() {
        return opened.values().stream().filter(Opened::enabled).map(Opened::source).toList();
    }

    /** Leer für unbekannte und abgeschaltete Quellen. */
    @Override
    public Optional<HarvestSource> find(String sourceId) {
        return active(sourceId).map(Opened::source);
    }

    @Override
    public List<SourceType> types() {
        return adapters.values().stream()
                .map(adapter -> new SourceType(adapter.type(), adapter.displayName(), adapter.needsCredentials(),
                        adapter.addressOption()))
                .toList();
    }

    private Optional<Opened> active(String sourceId) {
        return Optional.ofNullable(opened.get(sourceId)).filter(Opened::enabled);
    }

    // --- Quellen zur Laufzeit (#188) --------------------------------------------------------------------------------

    /**
     * Die Einstellungen aller Quellen in ihrer Reihenfolge, auch der abgeschalteten; ihr {@code toString} zeigt keine
     * Geheimnisse.
     */
    public List<SourceSettingsConfig> configs() {
        return opened.values().stream().map(Opened::config).toList();
    }

    /** Die Einstellungen einer Quelle, auch einer abgeschalteten; leer für unbekannte. */
    public Optional<SourceSettingsConfig> config(String sourceId) {
        return Optional.ofNullable(opened.get(sourceId)).map(Opened::config);
    }

    /** Ob die Quelle eingeschaltet ist; {@code false} auch für unbekannte. */
    public boolean enabled(String sourceId) {
        return active(sourceId).isPresent();
    }

    /**
     * Schaltet eine Quelle ab oder wieder ein, wie {@code SourceDefinitionStore#setEnabled} im Enterprise AI Client.
     * Einstellungen, Zugangsdaten und abgeleitete Daten bleiben; eine abgeschaltete Quelle ist für Suche, Lesen,
     * Aufträge und Export unbekannt, bis sie wieder eingeschaltet wird.
     *
     * @return ob sich etwas geändert hat
     * @throws UnknownSourceException wenn es keine Quelle mit dieser ID gibt
     */
    public synchronized boolean setEnabled(String sourceId, boolean enabled) {
        Opened current = opened.get(sourceId);
        if (current == null) {
            throw new UnknownSourceException(sourceId);
        }
        if (current.enabled() == enabled) {
            return false;
        }
        Map<String, Opened> next = new LinkedHashMap<>(opened);
        next.put(sourceId, current.enabled(enabled));
        opened = Collections.unmodifiableMap(next);
        return true;
    }

    /**
     * Probleme der Einstellungen, ohne etwas zu öffnen, wie beim Start (ohne Positionsangabe).
     *
     * @param replacing ob die Quelle eine vorhandene gleicher ID ersetzen soll; sonst ist eine vergebene ID ein Problem
     */
    public List<String> problems(SourceSettingsConfig config, boolean replacing) {
        return problems(config, replacing, List.of());
    }

    /**
     * Wie {@link #problems(SourceSettingsConfig, boolean)}, dazu gegen Quellen, die das Programm eingerichtet, aber
     * (noch) nicht geöffnet hat, etwa weil sie auf ihr Geheimnis warten oder beim Start abgelehnt wurden: Deren IDs
     * sind ebenso vergeben und deren Ziele ebenso belegt.
     *
     * @param alsoConfigured weitere eingerichtete Quellen; Einträge mit der ID von {@code config} zählen beim Ersetzen
     *                       nicht
     */
    public List<String> problems(SourceSettingsConfig config, boolean replacing,
                                 Collection<SourceSettingsConfig> alsoConfigured) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(alsoConfigured, "alsoConfigured");
        // IDs und Ziele getrennt sammeln: Auch ein eingerichteter Eintrag mit schon vergebener ID belegt sein Ziel.
        List<SourceSettingsConfig> known = new ArrayList<>();
        opened.values().forEach(source -> known.add(source.config()));
        alsoConfigured.stream().filter(Objects::nonNull).filter(other -> other.id() != null).forEach(known::add);
        Set<String> taken = new HashSet<>();
        known.forEach(other -> taken.add(other.id()));
        if (replacing) {
            taken.remove(config.id());
        }
        List<String> problems = new ArrayList<>(check(config, taken, adapters, secrets.external()));
        // Wie im Enterprise AI Client ist eine Quelle durch Typ und Ziel bestimmt: dasselbe Ziel zweimal wäre derselbe
        // Datenbestand unter zwei IDs.
        target(config).flatMap(target -> known.stream()
                        .filter(other -> !other.id().equals(config.id()))
                        .filter(other -> target(other).filter(target::equals).isPresent())
                        .findFirst())
                .ifPresent(other -> problems.add("Diese Wissensquelle ist bereits vorhanden (gleiches Ziel wie '"
                        + other.id() + "')"));
        return problems;
    }

    /** Paare von IDs mit demselben Ziel in {@code configs}, je Paar einmal; für die Warnung beim Start. */
    static List<String> sameTargets(List<SourceSettingsConfig> configs) {
        Map<String, String> first = new LinkedHashMap<>();
        List<String> pairs = new ArrayList<>();
        for (SourceSettingsConfig config : configs) {
            target(config).ifPresent(target -> {
                String earlier = first.putIfAbsent(target, config.id());
                if (earlier != null) {
                    pairs.add(earlier + "/" + config.id());
                }
            });
        }
        return pairs;
    }

    /**
     * Typ und Basisadresse ohne Anmeldedaten, Query und abschließenden Schrägstrich, Schema und Host klein, Standardport
     * weggelassen; leer ohne Basisadresse.
     */
    static Optional<String> target(SourceSettingsConfig config) {
        URI base = config.baseUrl();
        if (config.type() == null || base == null || base.getScheme() == null || base.getHost() == null) {
            return Optional.empty();
        }
        String scheme = base.getScheme().toLowerCase(Locale.ROOT);
        int port = base.getPort();
        boolean defaultPort = port < 0 || "https".equals(scheme) && port == 443 || "http".equals(scheme) && port == 80;
        String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        return Optional.of(config.type() + " " + scheme + "://" + base.getHost().toLowerCase(Locale.ROOT)
                + (defaultPort ? "" : ":" + port) + path);
    }

    /**
     * Fügt eine Quelle hinzu und öffnet sie; Verbindungen entstehen erst bei der ersten Anfrage.
     *
     * @throws InvalidSourceConfigurationException mit den Problemen; dann ändert sich nichts
     */
    public synchronized void add(SourceSettingsConfig config) {
        requireValid(config, false);
        Opened source = open(config, adapters.get(config.type()), context);
        Map<String, Opened> next = new LinkedHashMap<>(opened);
        next.put(config.id(), source);
        opened = Collections.unmodifiableMap(next);
    }

    /**
     * Ersetzt die Einstellungen einer vorhandenen Quelle derselben ID an ihrer Stelle; die bisherige schließt erst,
     * wenn die neue geöffnet ist.
     *
     * @return die bisherigen Einstellungen
     * @throws InvalidSourceConfigurationException mit den Problemen; dann bleibt die bisherige Quelle unverändert
     * @throws UnknownSourceException wenn es keine Quelle mit dieser ID gibt
     */
    public synchronized SourceSettingsConfig replace(SourceSettingsConfig config) {
        Opened previous = opened.get(Objects.requireNonNull(config, "config").id());
        if (previous == null) {
            throw new UnknownSourceException(config.id());
        }
        requireValid(config, true);
        ConfiguredSecrets.Saved saved = secrets.save(config.id());
        secrets.forget(config.id());
        Opened source;
        try {
            source = open(config, adapters.get(config.type()), context);
        } catch (RuntimeException e) {
            secrets.restore(saved);
            throw e;
        }
        Map<String, Opened> next = new LinkedHashMap<>(opened);
        next.put(config.id(), source.enabled(previous.enabled()));
        opened = Collections.unmodifiableMap(next);
        previous.close();
        return previous.config();
    }

    /**
     * Entfernt eine Quelle; ab dann ist sie unbekannt, ihre Zugangsdaten sind vergessen. Abgeleitete Daten (Cache,
     * Assets, Anreicherungen) entfernt der Aufrufer.
     *
     * @return die Einstellungen der entfernten Quelle, leer, wenn es keine gab
     */
    public synchronized Optional<SourceSettingsConfig> remove(String sourceId) {
        Opened previous = opened.get(sourceId);
        if (previous == null) {
            return Optional.empty();
        }
        Map<String, Opened> next = new LinkedHashMap<>(opened);
        next.remove(sourceId);
        opened = Collections.unmodifiableMap(next);
        previous.close();
        secrets.forget(sourceId);
        return Optional.of(previous.config());
    }

    /**
     * Öffnet die Einstellungen nur für {@code use}, ohne sie in den Katalog aufzunehmen, etwa zum Prüfen der
     * Verbindung vor dem Speichern. Mit denselben Netzwegen, Vertrauensquellen und demselben Anbieter von Zugangsdaten,
     * aber eigenen Zugangsdaten und synchronem Transport; danach geschlossen.
     *
     * @param replacing wie bei {@link #problems}
     * @throws InvalidSourceConfigurationException mit den Problemen der Einstellungen
     */
    public <T> T probe(SourceSettingsConfig config, boolean replacing, Function<HarvestSource, T> use) {
        Objects.requireNonNull(use, "use");
        List<String> problems = problems(config, replacing);
        if (!problems.isEmpty()) {
            throw new InvalidSourceConfigurationException(problems);
        }
        return probeUnchecked(config, use);
    }

    /**
     * Wie {@link #probe(SourceSettingsConfig, boolean, Function)} mit den Einstellungen einer vorhandenen Quelle (auch
     * abgeschaltet), ohne sie erneut zu prüfen: Sie wurden beim Start oder Hinzufügen angenommen.
     *
     * @throws UnknownSourceException ohne Quelle dieser ID
     */
    public <T> T probe(String sourceId, Function<HarvestSource, T> use) {
        Objects.requireNonNull(use, "use");
        return probeUnchecked(config(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId)), use);
    }

    private <T> T probeUnchecked(SourceSettingsConfig config, Function<HarvestSource, T> use) {
        SourceAdapter.Context probing = new SourceAdapter.Context(secrets.fresh(), routes, context.userAgent(),
                context.trustBundles(), null, context.proxyCredentials());
        Opened source = open(config, adapters.get(config.type()), probing);
        try {
            return use.apply(source.source());
        } finally {
            source.close();
        }
    }

    private void requireValid(SourceSettingsConfig config, boolean replacing) {
        List<String> problems = problems(config, replacing);
        if (!problems.isEmpty()) {
            throw new InvalidSourceConfigurationException(problems);
        }
    }

    /**
     * Delegierte Aufrufe an die Confluence der Quelle mit dem Token des Nutzers (#24), über dieselbe Route und
     * dasselbe Client-Zertifikat wie das Ernten; leer für unbekannte Quellen.
     */
    public Optional<DelegatedConfluenceHttp> delegatedHttp(String sourceId) {
        return active(sourceId).map(Opened::delegated);
    }

    /**
     * Die Proxy-Route zur Basisadresse der Quelle in einer Zeile ({@code DIRECT}, {@code PROXY host:port …}), für die
     * Auftragsdiagnose; leer für unbekannte Quellen und Quellen ohne HTTP-Adresse. Nutzt die gemerkte Route und wirft
     * nicht.
     */
    public Optional<String> route(String sourceId) {
        Opened source = active(sourceId).orElse(null);
        URI baseUrl = source == null ? null : source.baseUrl();
        if (baseUrl == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(routes.routeFor(baseUrl).describe());
        } catch (RuntimeException e) {
            return Optional.of("nicht bestimmbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Der Stufentest des Netzwegs mit derselben Route und Proxy-Anmeldung wie die Quellen (#189). */
    public ConnectionProbe connectionProbe(Duration timeout) {
        return new ConnectionProbe(routes, context.proxyCredentials(), timeout);
    }

    /**
     * Das Serververtrauen einer Quelle: ihr {@code trust-bundle}, sonst das Vertrauen ohne Bundle; {@code null} heißt
     * JVM-Truststore.
     *
     * @throws IllegalArgumentException wenn das Bundle nicht konfiguriert ist
     */
    public X509TrustManager trustFor(SourceSettingsConfig config) {
        return config.trustBundle() == null ? context.trustBundles().defaultTrust()
                : context.trustBundles().trustManager(config.trustBundle());
    }

    /** Die Proxy-Route je Ziel aller Quellen, auch für den KI-Dienst ({@code source.ai}, ADR 0011). */
    public ProxyRoutes routes() {
        return routes;
    }

    /** Gibt die Verbindungspools der delegierten und asynchronen Aufrufe frei (Spring ruft das beim Herunterfahren). */
    @Override
    public void close() {
        opened.values().forEach(Opened::close);
        if (async != null) {
            async.close();
        }
        if (authenticator != null) {
            authenticator.uninstall();
        }
    }
}

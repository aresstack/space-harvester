package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.source.confluence.AsyncSnapshotTransports;
import com.aresstack.spaceharvester.source.confluence.DelegatedConfluenceHttp;
import com.aresstack.spaceharvester.source.network.InvalidNetworkConfigurationException;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baut aus der Konfiguration die Quellen und prüft sie dabei vollständig: Fehler aller Quellen werden gesammelt
 * und gemeinsam gemeldet, damit ein Start nicht an einer Quelle nach der anderen scheitert. Verbindungen werden erst
 * bei der ersten Anfrage aufgebaut; der Dienst startet also auch, wenn eine Quelle gerade nicht erreichbar ist.
 */
public final class ConfiguredSourceCatalog implements SourceCatalog, AutoCloseable {

    /** Quellentypen ohne eigene Registrierung: Confluence und lokale Dateien. */
    public static final List<SourceAdapter> DEFAULT_ADAPTERS = List.of(new ConfluenceSourceAdapter(),
            new LocalFilesSourceAdapter());

    /** Quellentypen, die der Space Harvester ohne eigene Registrierung kennt. */
    public static final Set<String> TYPES = Set.of(ConfluenceSourceAdapter.TYPE, LocalFilesSourceAdapter.TYPE);

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredSourceCatalog.class);

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /** Transport für native Seiten-Snapshots ({@code space-harvester.network.async-transport}, #47). */
    public enum AsyncTransport {
        /** Nur der synchrone Transport (Rückfallpfad). */
        DISABLED,
        /** Snapshots nicht blockierend über Spring WebClient auf Reactor Netty (Standard des Dienstes). */
        WEB_CLIENT
    }

    private final Map<String, HarvestSource> sources;
    private final Map<String, DelegatedConfluenceHttp> delegated;
    private final AsyncSnapshotTransports async;

    private ConfiguredSourceCatalog(Map<String, HarvestSource> sources,
                                    Map<String, DelegatedConfluenceHttp> delegated, AsyncSnapshotTransports async) {
        this.sources = sources;
        this.delegated = delegated;
        this.async = async;
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
        Map<String, SourceAdapter> byType = new LinkedHashMap<>();
        for (SourceAdapter adapter : adapters) {
            if (byType.putIfAbsent(adapter.type(), adapter) != null) {
                throw new IllegalArgumentException("Quellentyp doppelt registriert: " + adapter.type());
            }
        }
        ProxyRoutes routes = ProxyRoutes.from(proxy);
        LOG.info("Proxy-Route der Quellen: {}", routes.describe());
        List<String> problems = new ArrayList<>();
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        AsyncSnapshotTransports async = asyncTransport == AsyncTransport.WEB_CLIENT
                ? new AsyncSnapshotTransports(secrets, routes, userAgent) : null;
        LOG.info("Seiten-Snapshots: {}", async == null ? "synchron" : "asynchron über WebClient");
        SourceAdapter.Context context = new SourceAdapter.Context(secrets, routes, userAgent, trustBundles, async);
        Map<String, HarvestSource> sources = new LinkedHashMap<>();
        Map<String, DelegatedConfluenceHttp> delegated = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < configs.size(); i++) {
            SourceSettingsConfig config = configs.get(i);
            String label = "sources[" + i + "]" + (config.id() == null ? "" : " (id=" + config.id() + ")");
            List<String> own = check(config, seenIds, byType);
            if (config.id() != null) {
                seenIds.add(config.id());
            }
            SourceAdapter adapter = config.type() == null ? null : byType.get(config.type());
            if (adapter != null) {
                own = new ArrayList<>(own);
                own.addAll(adapter.check(config));
            }
            if (!own.isEmpty()) {
                own.forEach(problem -> problems.add(label + ": " + problem));
                continue;
            }
            String title = config.title() == null || config.title().isBlank() ? config.id() : config.title();
            try {
                SourceAdapter.OpenedSource opened = adapter.open(config, context);
                sources.put(config.id(), new KnowledgeSourceHarvestSource(config.id(), config.type(), title,
                        opened.port(), async == null ? null : async.inFlight()));
                if (opened.delegated() != null) {
                    delegated.put(config.id(), opened.delegated());
                }
            } catch (InvalidSourceConfigurationException e) {
                e.problems().forEach(problem -> problems.add(label + ": " + problem));
            }
        }
        if (!problems.isEmpty()) {
            delegated.values().forEach(DelegatedConfluenceHttp::close);
            if (async != null) {
                async.close();
            }
            throw new InvalidSourceConfigurationException(problems);
        }
        return new ConfiguredSourceCatalog(sources, delegated, async);
    }

    private static List<String> check(SourceSettingsConfig config, Set<String> knownIds,
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

    @Override
    public List<HarvestSource> all() {
        return List.copyOf(sources.values());
    }

    @Override
    public Optional<HarvestSource> find(String sourceId) {
        return Optional.ofNullable(sources.get(sourceId));
    }

    /**
     * Delegierte Aufrufe an die Confluence der Quelle mit dem Token des Nutzers (#24), über dieselbe Route und
     * dasselbe Client-Zertifikat wie das Ernten; leer für unbekannte Quellen.
     */
    public Optional<DelegatedConfluenceHttp> delegatedHttp(String sourceId) {
        return Optional.ofNullable(delegated.get(sourceId));
    }

    /** Gibt die Verbindungspools der delegierten und asynchronen Aufrufe frei (Spring ruft das beim Herunterfahren). */
    @Override
    public void close() {
        delegated.values().forEach(DelegatedConfluenceHttp::close);
        if (async != null) {
            async.close();
        }
    }
}

package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceProvider;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.CertificateStoreType;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.ClientCertificate;
import com.aresstack.spaceharvester.source.confluence.AsyncSnapshotTransports;
import com.aresstack.spaceharvester.source.confluence.ClientCertificateException;
import com.aresstack.spaceharvester.source.confluence.ConfluenceSources;
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
import javax.net.ssl.X509TrustManager;

/**
 * Baut aus der Konfiguration die Quellen und prüft sie dabei vollständig: Fehler aller Quellen werden gesammelt
 * und gemeinsam gemeldet, damit ein Start nicht an einer Quelle nach der anderen scheitert. Verbindungen werden erst
 * bei der ersten Anfrage aufgebaut; der Dienst startet also auch, wenn eine Quelle gerade nicht erreichbar ist.
 */
public final class ConfiguredSourceCatalog implements SourceCatalog, AutoCloseable {

    /** Quellentypen, die der Space Harvester kennt. */
    public static final Set<String> TYPES = Set.of("confluence");

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredSourceCatalog.class);

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /** Schlüssel, die der Katalog selbst setzt und die nicht über {@code options} kommen dürfen. */
    private static final Set<String> RESERVED_OPTIONS = Set.of("baseUrl", "title", "credentialRef",
            "clientCertificate.alias", "clientCertificate.use", "clientCertificate.keyStoreFile",
            "clientCertificate.keyStorePasswordRef");

    /** Transport für native Seiten-Snapshots ({@code space-harvester.network.async-transport}, #47). */
    public enum AsyncTransport {
        /** Nur der synchrone Transport (Standard, Rückfallpfad). */
        DISABLED,
        /** Snapshots zusätzlich nicht blockierend über Spring WebClient auf Reactor Netty. */
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
        ProxyRoutes routes = ProxyRoutes.from(proxy);
        LOG.info("Proxy-Route der Quellen: {}", routes.describe());
        List<String> problems = new ArrayList<>();
        ConfiguredSecrets secrets = new ConfiguredSecrets();
        AsyncSnapshotTransports async = asyncTransport == AsyncTransport.WEB_CLIENT
                ? new AsyncSnapshotTransports(secrets, routes, userAgent) : null;
        LOG.info("Seiten-Snapshots: {}", async == null ? "synchron" : "zusätzlich asynchron über WebClient");
        Map<String, HarvestSource> sources = new LinkedHashMap<>();
        Map<String, DelegatedConfluenceHttp> delegated = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < configs.size(); i++) {
            SourceSettingsConfig config = configs.get(i);
            String label = "sources[" + i + "]" + (config.id() == null ? "" : " (id=" + config.id() + ")");
            List<String> own = check(config, seenIds);
            if (config.id() != null) {
                seenIds.add(config.id());
            }
            if (!own.isEmpty()) {
                own.forEach(problem -> problems.add(label + ": " + problem));
                continue;
            }
            switch (config.auth().type()) {
                case BASIC -> secrets.put(config.id(), config.auth().username(), config.auth().password());
                case PAT -> secrets.put(config.id(), null, config.auth().token());
                case NONE -> { }
            }
            ClientCertificate certificate = config.clientCertificate();
            if (certificate.enabled() && certificate.type() == CertificateStoreType.PKCS12) {
                secrets.put(ConfiguredSecrets.certificateRefFor(config.id()), null, certificate.password());
            }
            X509TrustManager trust;
            try {
                trust = config.trustBundle() == null ? null : trustBundles.trustManager(config.trustBundle());
            } catch (IllegalArgumentException e) {
                problems.add(label + ": trust-bundle: " + e.getMessage());
                continue;
            }
            KnowledgeSourceProvider provider = ConfluenceSources.provider(secrets, routes, userAgent, trust,
                    certificate.enabled() ? certificate.thumbprint() : null, async);
            SourceSettings settings = settings(config);
            List<String> invalid = provider.validate(settings);
            if (!invalid.isEmpty()) {
                invalid.forEach(problem -> problems.add(label + ": " + problem));
                continue;
            }
            String title = config.title() == null || config.title().isBlank() ? config.id() : config.title();
            try {
                // Mit mTLS lädt open() das Zertifikat sofort: Fehler verhindern den Start, statt die Quelle
                // stillschweigend ohne Client-Zertifikat zu betreiben.
                sources.put(config.id(), new KnowledgeSourceHarvestSource(config.id(), config.type(), title,
                        provider.open(SourceAddress.of(config.id()), settings)));
                delegated.put(config.id(), ConfluenceSources.delegatedHttp(config.baseUrl(), secrets, routes,
                        userAgent, trust, certificate.enabled() ? certificate.thumbprint() : null,
                        certificate.enabled() ? certificate.alias().trim() : null,
                        certificate.enabled() && certificate.type() == CertificateStoreType.PKCS12
                                ? certificate.path() : null,
                        certificate.enabled() && certificate.type() == CertificateStoreType.PKCS12
                                ? ConfiguredSecrets.certificateRefFor(config.id()) : null));
            } catch (ClientCertificateException e) {
                problems.add(label + ": client-certificate: " + e.getMessage());
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

    private static List<String> check(SourceSettingsConfig config, Set<String> knownIds) {
        List<String> problems = new ArrayList<>();
        if (config.id() == null || !ID.matcher(config.id()).matches()) {
            problems.add("id fehlt oder ist ungültig (erlaubt: Kleinbuchstaben, Ziffern, Bindestrich; z. B. "
                    + "'confluence')");
        } else if (knownIds.contains(config.id())) {
            problems.add("id ist doppelt vergeben");
        }
        if (config.type() == null || !TYPES.contains(config.type())) {
            problems.add("type fehlt oder ist unbekannt (bekannt: " + String.join(", ", TYPES) + ")");
        }
        if (config.baseUrl() == null) {
            problems.add("base-url fehlt");
        }
        problems.addAll(checkAuth(config.auth()));
        problems.addAll(checkCertificate(config.clientCertificate()));
        for (String key : config.options().keySet()) {
            if (RESERVED_OPTIONS.contains(key)) {
                problems.add("options." + key + " ist nicht erlaubt; dafür gibt es eigene Felder");
            }
        }
        return problems;
    }

    private static List<String> checkAuth(SourceSettingsConfig.Auth auth) {
        boolean username = present(auth.username());
        boolean password = present(auth.password());
        boolean token = present(auth.token());
        List<String> problems = new ArrayList<>();
        if (auth.type() == null) {
            problems.add("auth.type fehlt (none, basic oder pat)");
            return problems;
        }
        switch (auth.type()) {
            case NONE -> {
                if (username || password || token) {
                    problems.add("auth.type ist none, aber Zugangsdaten sind gesetzt; auth.type basic oder pat angeben");
                }
            }
            case BASIC -> {
                if (!username || !password) {
                    problems.add("auth.type basic braucht auth.username und auth.password");
                }
                if (token) {
                    problems.add("auth.token passt nicht zu auth.type basic");
                }
            }
            case PAT -> {
                if (!token) {
                    problems.add("auth.type pat braucht auth.token");
                }
                if (username || password) {
                    problems.add("auth.username/auth.password passen nicht zu auth.type pat");
                }
            }
        }
        return problems;
    }

    private static List<String> checkCertificate(ClientCertificate certificate) {
        List<String> problems = new ArrayList<>();
        boolean path = certificate.path() != null;
        boolean password = present(certificate.password());
        if (!certificate.enabled()) {
            // Abgeschaltet gilt Standard-TLS; die übrigen Angaben dürfen zum Wiedereinschalten stehen bleiben.
            return problems;
        }
        if (certificate.type() == null) {
            problems.add("client-certificate.type fehlt (pkcs12 oder windows-my)");
        }
        if (!present(certificate.alias())) {
            problems.add("client-certificate.alias fehlt");
        }
        if (certificate.type() == CertificateStoreType.PKCS12) {
            if (!path) {
                problems.add("client-certificate.type pkcs12 braucht client-certificate.path");
            }
            if (!password) {
                problems.add("client-certificate.type pkcs12 braucht client-certificate.password");
            }
        }
        if (certificate.type() == CertificateStoreType.WINDOWS_MY && (path || password)) {
            problems.add("client-certificate.path/client-certificate.password passen nicht zu client-certificate.type "
                    + "windows-my");
        }
        if (present(certificate.thumbprint()) && !ConfluenceSources.validThumbprint(certificate.thumbprint())) {
            problems.add("client-certificate.thumbprint ist kein SHA-1- (40) oder SHA-256-Fingerabdruck (64 "
                    + "Hex-Zeichen)");
        }
        return problems;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static SourceSettings settings(SourceSettingsConfig config) {
        Map<String, String> values = new LinkedHashMap<>(config.options());
        values.put("baseUrl", config.baseUrl().toString());
        if (config.title() != null) {
            values.put("title", config.title());
        }
        if (config.auth().type() != SourceSettingsConfig.AuthType.NONE) {
            values.put("credentialRef", ConfiguredSecrets.refFor(config.id()).id());
        }
        ClientCertificate certificate = config.clientCertificate();
        // Ohne eingeschaltetes Zertifikat ausdrücklich ohne mTLS, damit der Adapter keinen Alias im Tresor sucht.
        values.put("clientCertificate.use", Boolean.toString(certificate.enabled()));
        if (certificate.enabled()) {
            values.put("clientCertificate.alias", certificate.alias().trim());
            if (certificate.type() == CertificateStoreType.PKCS12) {
                values.put("clientCertificate.keyStoreFile", certificate.path().toString());
                values.put("clientCertificate.keyStorePasswordRef",
                        ConfiguredSecrets.certificateRefFor(config.id()).id());
            }
        }
        return SourceSettings.of(values);
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

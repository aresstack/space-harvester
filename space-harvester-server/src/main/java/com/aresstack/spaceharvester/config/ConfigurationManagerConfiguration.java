package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.api.ConfigurationOverlay;
import com.aresstack.spaceharvester.api.ConfigurationResponse;
import com.aresstack.spaceharvester.api.NetworkDescription;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.application.configuration.StorageSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * Konfiguration zur Laufzeit (ADR 0015): ein {@link ConfigurationManager} mit der Startkonfiguration aus den
 * gebundenen Properties ({@code space-harvester.ai}, {@code .search}, {@code .sync}, {@code .cache},
 * {@code .network}). Aktualität, Frist, Freigabe an den KI-Dienst, Reranker, Embedding-Modell, Zeitplan und Nachholen
 * beim Start folgen ihm über {@link LiveConfiguration}; was noch nicht live wirkt, nennt docs/CONFIGURATION.md.
 *
 * <p>Eine ungültige Startkonfiguration (etwa ein Chat-Modell ohne {@code ai.base-url}) verhindert den Start, wie
 * überall im Server.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProperties.class, SearchProperties.class, SyncProperties.class,
        CacheProperties.class, SpaceHarvesterProperties.class})
class ConfigurationManagerConfiguration {

    @Bean
    ConfigurationManager configurationManager(AiProperties ai, SearchProperties search, SyncProperties sync,
                                              CacheProperties cache, SpaceHarvesterProperties harvester,
                                              @Value("${space-harvester.cache.max-age:0s}") Duration maxAge,
                                              @Value("${space-harvester.cache.version-check:true}")
                                              boolean versionCheck, Environment environment) {
        return new ConfigurationManager(initial(ai, search, sync, cache, harvester.network(), maxAge, versionCheck,
                Binder.get(environment)));
    }

    /** Die wirksame Konfiguration für die Beans, die ihr zur Laufzeit folgen. */
    @Bean
    LiveConfiguration liveConfiguration(ConfigurationManager manager) {
        return new LiveConfiguration(manager);
    }

    /** Proxy und TLS für {@code GET /api/v1/configuration}, ohne Proxy-Passwort. */
    @Bean
    NetworkDescription networkDescription() {
        return ConfigurationManagerConfiguration::describe;
    }

    /**
     * Die Konfiguration aus den gebundenen Properties, beim Start und nach einem Refresh. Ereignisse und Abgleich vor
     * jeder Suche ({@code sync.events}, {@code sync.before-search}) liest {@link #withSyncRules} aus dem Environment;
     * sie beginnen aus und lassen sich auch über {@code POST /api/v1/configuration} setzen.
     *
     * @param maxAge       {@code space-harvester.cache.max-age}
     * @param versionCheck {@code space-harvester.cache.version-check}
     * @throws IllegalArgumentException wenn die Cache-Frist außerhalb von 0 bis 365 Tagen liegt
     */
    static HarvesterConfiguration initial(AiProperties ai, SearchProperties search, SyncProperties sync,
                                          CacheProperties cache, SpaceHarvesterProperties.Network network,
                                          Duration maxAge, boolean versionCheck, Binder binder) {
        CacheFreshness freshness = new CacheFreshness(maxAge == null ? Duration.ZERO : maxAge, versionCheck);
        HarvesterConfiguration base = new HarvesterConfiguration(ai.toSettings(),
                new SearchSettings(search.semantic(), search.rerank(), search.prefetchHits(), freshness),
                new SyncSettings(false, sync.interval(), sync.spaces(), false, sync.withheldRetention()),
                new NetworkSettings(network.proxy().toSettings(), network.tls().toSettings()),
                new StorageSettings(cache.directory()));
        return withSyncRules(base, binder);
    }

    /** Schlüssel, die {@link SyncProperties} nicht kennt und der Core selbst liest. */
    private static final List<String> SYNC_KEYS = List.of(SyncSettings.EVENTS, SyncSettings.BEFORE_SEARCH,
            SyncSettings.CATCH_UP_ON_START, SyncSettings.MODE, SyncSettings.INVENTORY_INTERVAL,
            SyncSettings.BACKGROUND_FILL, SyncSettings.BACKGROUND_PARALLELISM, SearchSettings.CACHE_REFRESH_STRATEGY);

    /**
     * Ergänzt Änderungsquellen, Betriebsart, Bestandsinventur, Aktualisierungsstrategie und die Regeln je Quelle
     * ({@code space-harvester.sync.sources.<Quelle>.*}) aus dem Environment, gelesen wie in
     * {@link HarvesterConfiguration#fromProperties}.
     *
     * @throws com.aresstack.spaceharvester.application.configuration.ConfigurationRejectedException bei ungültigen
     *         Werten
     */
    static HarvesterConfiguration withSyncRules(HarvesterConfiguration base, Binder binder) {
        Properties properties = new Properties();
        // Grundlage, damit Regeln je Quelle und Strategie gegen dieselben globalen Werte geprüft werden.
        properties.putAll(ConfigurationOverlay.toProperties(base));
        for (String key : SYNC_KEYS) {
            binder.bind(HarvesterConfiguration.PREFIX + key, String.class)
                    .ifBound(value -> properties.setProperty(key, value));
        }
        binder.bind(HarvesterConfiguration.PREFIX + SyncSettings.SOURCES, SOURCE_RULES).ifBound(rules ->
                rules.forEach((id, fields) -> fields.forEach((field, value) ->
                        properties.setProperty(SourceSyncOverride.key(id, field), value))));
        HarvesterConfiguration read = HarvesterConfiguration.fromProperties(properties).orThrow();
        return base.withSync(read.sync()).withSearch(read.search());
    }

    private static final Bindable<Map<String, Map<String, String>>> SOURCE_RULES = Bindable.of(
            ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                    ResolvableType.forClassWithGenerics(Map.class, String.class, String.class)));

    static ConfigurationResponse.Network describe(NetworkSettings network) {
        ProxySettings p = network.proxy();
        ProxySettings.Authentication auth = p.authentication();
        TrustSettings t = network.trust();
        return new ConfigurationResponse.Network(
                new ConfigurationResponse.Proxy(p.mode().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        ConfigurationResponse.withoutSecrets(p.pacUrl()), p.proxyHost(), p.proxyPort(),
                        p.nonProxyHosts(), ConfigurationResponse.text(p.resolveTimeout()),
                        ConfigurationResponse.text(p.cacheTtl()), ConfigurationResponse.text(p.failureTtl()),
                        auth == null ? null : auth.username(), auth != null && auth.password() != null,
                        p.pacDiscoveryScript() != null),
                new ConfigurationResponse.Tls(t.useJvmDefault(), t.useWindowsRoot(), t.useWindowsCaStores(),
                        t.caCertificatesFile() == null ? null : t.caCertificatesFile().toString()));
    }

    /**
     * Mit Spring Cloud Context (kommt mit {@code spring-cloud-starter-config}): ein geändertes Environment, etwa nach
     * einem Refresh aus dem Config-Server, geht als neue Konfiguration an den Manager. Ein Refresh-Endpunkt wird dafür
     * nicht freigegeben; {@code management.endpoints.web.exposure} bleibt bei {@code health,info}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
    static class EnvironmentRefresh {

        @Bean
        EnvironmentConfigurationRefresh environmentConfigurationRefresh(ConfigurationManager manager,
                                                                        Environment environment) {
            return new EnvironmentConfigurationRefresh(manager, environment);
        }
    }
}

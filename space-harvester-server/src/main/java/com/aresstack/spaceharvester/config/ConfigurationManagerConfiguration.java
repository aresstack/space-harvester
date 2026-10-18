package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.api.ConfigurationResponse;
import com.aresstack.spaceharvester.api.NetworkDescription;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.NetworkSettings;
import com.aresstack.spaceharvester.application.configuration.SearchSettings;
import com.aresstack.spaceharvester.application.configuration.StorageSettings;
import com.aresstack.spaceharvester.application.configuration.SyncSettings;
import com.aresstack.spaceharvester.application.query.CacheFreshness;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Konfiguration zur Laufzeit (ADR 0015): ein {@link ConfigurationManager} mit der Startkonfiguration aus den
 * gebundenen Properties ({@code space-harvester.ai}, {@code .search}, {@code .sync}, {@code .cache},
 * {@code .network}). Die bestehenden Beans (KI-Dienst, Suche, Zeitplan) bleiben unverändert und lesen ihre Properties
 * weiter selbst; wer zur Laufzeit wechseln will, meldet sich beim Manager als
 * {@link com.aresstack.spaceharvester.application.configuration.ConfigurationListener} an.
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
                                              boolean versionCheck) {
        return new ConfigurationManager(initial(ai, search, sync, cache, harvester.network(), maxAge, versionCheck));
    }

    /** Proxy und TLS für {@code GET /api/v1/configuration}, ohne Proxy-Passwort. */
    @Bean
    NetworkDescription networkDescription() {
        return ConfigurationManagerConfiguration::describe;
    }

    /**
     * Die Konfiguration aus den gebundenen Properties, beim Start und nach einem Refresh. Ereignisse und Abgleich vor
     * jeder Suche ({@code sync.events}, {@code sync.before-search}) kennt der Server noch nicht als Properties; sie
     * beginnen aus und lassen sich über {@code POST /api/v1/configuration} setzen.
     *
     * @param maxAge       {@code space-harvester.cache.max-age}
     * @param versionCheck {@code space-harvester.cache.version-check}
     * @throws IllegalArgumentException wenn die Cache-Frist außerhalb von 0 bis 365 Tagen liegt
     */
    static HarvesterConfiguration initial(AiProperties ai, SearchProperties search, SyncProperties sync,
                                          CacheProperties cache, SpaceHarvesterProperties.Network network,
                                          Duration maxAge, boolean versionCheck) {
        CacheFreshness freshness = new CacheFreshness(maxAge == null ? Duration.ZERO : maxAge, versionCheck);
        return new HarvesterConfiguration(ai.toSettings(),
                new SearchSettings(search.semantic(), search.rerank(), search.prefetchHits(), freshness),
                new SyncSettings(false, sync.interval(), sync.spaces(), false, sync.withheldRetention()),
                new NetworkSettings(network.proxy().toSettings(), network.tls().toSettings()),
                new StorageSettings(cache.directory()));
    }

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

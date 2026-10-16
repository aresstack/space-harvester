package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Baut die Quellen aus {@code space-harvester.sources} mit der Proxy-Route aus {@code space-harvester.network.proxy}.
 * Eine fehlerhafte Quellen- oder Proxykonfiguration verhindert den Start mit
 * einer Liste aller Probleme; eine nicht erreichbare Quelle nicht (Verbindungen entstehen erst bei Anfragen).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpaceHarvesterProperties.class)
class SourceConfiguration {

    @Bean
    SourceCatalog sourceCatalog(SpaceHarvesterProperties properties, ObjectProvider<BuildProperties> build) {
        BuildProperties info = build.getIfAvailable();
        String userAgent = "space-harvester/" + (info != null ? info.getVersion() : "unknown");
        return ConfiguredSourceCatalog.of(
                properties.sources().stream().map(SpaceHarvesterProperties.Source::toSettings).toList(), userAgent,
                properties.network().proxy().toSettings());
    }

    @Bean
    SourceQueries sourceQueries(SourceCatalog catalog) {
        return new SourceQueries(catalog);
    }
}

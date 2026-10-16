package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.util.Arrays;
import javax.net.ssl.X509TrustManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
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
    ConfiguredSourceCatalog sourceCatalog(SpaceHarvesterProperties properties, ObjectProvider<BuildProperties> build,
                                ObjectProvider<SslBundles> sslBundles) {
        BuildProperties info = build.getIfAvailable();
        String userAgent = "space-harvester/" + (info != null ? info.getVersion() : "unknown");
        SslBundles bundles = sslBundles.getIfAvailable();
        return ConfiguredSourceCatalog.of(
                properties.sources().stream().map(SpaceHarvesterProperties.Source::toSettings).toList(), userAgent,
                bundles == null ? TrustBundles.NONE : trustBundles(bundles), properties.network().proxy().toSettings());
    }

    /**
     * Server-Vertrauen einer Quelle aus {@code spring.ssl.bundle.*}: nur der Truststore des Bundles wird verwendet,
     * ein Keystore darin bleibt unbeachtet (das Client-Zertifikat steht unter {@code client-certificate}).
     */
    static TrustBundles trustBundles(SslBundles bundles) {
        return name -> {
            try {
                return Arrays.stream(bundles.getBundle(name).getManagers().getTrustManagers())
                        .filter(X509TrustManager.class::isInstance).map(X509TrustManager.class::cast).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("SSL-Bundle '" + name
                                + "' hat keinen X.509-Trust-Manager"));
            } catch (NoSuchSslBundleException e) {
                throw new IllegalArgumentException("SSL-Bundle '" + name + "' ist nicht konfiguriert", e);
            }
        };
    }

    @Bean
    SourceQueries sourceQueries(SourceCatalog catalog) {
        return new SourceQueries(catalog);
    }
}

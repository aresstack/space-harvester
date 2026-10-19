package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.DelegatedSourceAccessFactory;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.ConfluenceDelegatedAccess;
import com.aresstack.spaceharvester.source.ConfluenceSourceAdapter;
import com.aresstack.spaceharvester.source.EacSourceAdapters;
import com.aresstack.spaceharvester.source.KnowledgeSourceProviderAdapter;
import com.aresstack.spaceharvester.source.LocalFilesSourceAdapter;
import com.aresstack.spaceharvester.source.MediaWikiSources;
import com.aresstack.spaceharvester.source.SourceAdapter;
import com.aresstack.spaceharvester.source.SourceDiagnostics;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
import java.util.Arrays;
import java.util.List;
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

    /** Quellentyp {@code confluence}. Weitere Typen kommen als weitere {@link SourceAdapter}-Beans dazu (#18). */
    @Bean
    ConfluenceSourceAdapter confluenceSourceAdapter() {
        return new ConfluenceSourceAdapter();
    }

    /** Quellentyp {@code files}: lokale Verzeichnisse. */
    @Bean
    LocalFilesSourceAdapter localFilesSourceAdapter() {
        return new LocalFilesSourceAdapter();
    }

    /** Quellentyp {@code mediawiki}: Seiten einer MediaWiki-Site über die {@code api.php} (#187). */
    @Bean
    KnowledgeSourceProviderAdapter mediaWikiSourceAdapter() {
        return MediaWikiSources.adapter();
    }

    /** Kopien aus dem Enterprise AI Client ohne eigenen Ordner (#187); Zugangsdaten über den SecretProvider (#188). */
    @Bean
    SourceAdapter ftpSourceAdapter() {
        return EacSourceAdapters.ftp();
    }

    @Bean
    SourceAdapter jesSourceAdapter() {
        return EacSourceAdapters.jes();
    }

    @Bean
    SourceAdapter ndvSourceAdapter() {
        return EacSourceAdapters.ndv();
    }

    @Bean
    SourceAdapter sharePointSourceAdapter() {
        return EacSourceAdapters.sharePoint();
    }

    @Bean
    SourceAdapter betaViewSourceAdapter() {
        return EacSourceAdapters.betaView();
    }

    /**
     * Vertrauen der Quellen und des KI-Dienstes: {@code trust-bundle} aus {@code spring.ssl.bundle.*}, ohne Bundle
     * {@code space-harvester.network.tls} (#189). Eine Bean, damit die Windows-Speicher nur einmal gelesen werden.
     */
    @Bean
    TrustBundles sourceTrustBundles(SpaceHarvesterProperties properties, ObjectProvider<SslBundles> sslBundles) {
        TrustSettings tls = properties.network().tls().toSettings();
        List<String> problems = TrustPolicy.problems(tls);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("space-harvester.network." + String.join("; ", problems));
        }
        SslBundles bundles = sslBundles.getIfAvailable();
        return TrustBundles.withDefault(bundles == null ? TrustBundles.NONE : trustBundles(bundles),
                TrustPolicy.defaultTrust(tls));
    }

    @Bean
    ConfiguredSourceCatalog sourceCatalog(SpaceHarvesterProperties properties, ObjectProvider<BuildProperties> build,
                                TrustBundles trustBundles, ObjectProvider<SourceAdapter> adapters) {
        BuildProperties info = build.getIfAvailable();
        String userAgent = "space-harvester/" + (info != null ? info.getVersion() : "unknown");
        return ConfiguredSourceCatalog.of(
                properties.sources().stream().map(SpaceHarvesterProperties.Source::toSettings).toList(), userAgent,
                trustBundles, properties.network().proxy().toSettings(),
                properties.network().asyncTransport(), adapters.orderedStream().toList());
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

    /** Verbindungsdiagnose in Stufen (#189), {@code GET /api/v1/sources/{id}/diagnosis}. */
    @Bean
    SourceDiagnostics sourceDiagnostics(ConfiguredSourceCatalog catalog) {
        return new SourceDiagnostics(catalog);
    }

    /** Prüfung durchgereichter Endnutzer-Anmeldungen bei Confluence-Quellen (#24, Teil C). */
    @Bean
    DelegatedSourceAccessFactory confluenceDelegatedAccess(ConfiguredSourceCatalog catalog) {
        return ConfluenceDelegatedAccess.factory(catalog);
    }

    @Bean
    SourceQueries sourceQueries(SourceCatalog catalog) {
        return new SourceQueries(catalog);
    }
}

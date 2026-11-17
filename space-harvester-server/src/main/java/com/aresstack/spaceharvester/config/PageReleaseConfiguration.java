package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.release.AiRelease;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageReleases;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Die KI-Freigabe je Seite (ADR 0028): Mit Cache ({@code space-harvester.cache.directory}) liegt die Liste der
 * freigegebenen Seiten unter {@value #PAGE_RELEASES}/ neben den übrigen Indizes und überdauert einen Neustart; ohne
 * Cache lebt sie nur im Speicher. Die Freigabeprüfung folgt der wirksamen Konfiguration
 * ({@code ai.external-processing-sources}) ohne Neustart.
 */
@Configuration(proxyBeanMethods = false)
class PageReleaseConfiguration {

    /** Unterverzeichnis der Liste der freigegebenen Seiten. */
    static final String PAGE_RELEASES = "page-releases";

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "space-harvester.cache", name = "directory")
    LucenePageReleases pageReleases(CacheProperties properties) {
        return LucenePageReleases.open(properties.directory().resolve(PAGE_RELEASES));
    }

    @Bean
    AiRelease aiRelease(LiveConfiguration live, ObjectProvider<LucenePageReleases> stored) {
        PageReleases pages = stored.getIfAvailable();
        return new AiRelease(live::mayProcessExternally, pages == null ? PageReleases.inMemory() : pages);
    }
}
